package com.casino.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.casino.auth.config.ThrottleProperties;
import com.casino.auth.config.ThrottleProperties.Limit;
import com.casino.auth.exception.TooManyAttemptsException;
import com.casino.auth.repository.AuthThrottleRepository;
import com.casino.auth.repository.ThrottleWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Login and registration throttling with fixed-window counters in PostgreSQL (week-02.md §6, §7.7).
 *
 * <p>Intended use (login in W2-08, registration in W2-07):
 * <ul>
 *   <li><b>Login:</b> {@link #checkAllowed} with the account + IP and the IP key <em>before</em> the
 *       password is verified (AC-6.5), so a blocked attempt costs no BCrypt work and is answered the
 *       same way for existing and unknown accounts; {@link #recordFailure} with both keys after a
 *       failed login; {@link #reset} of the account + IP key after a successful one (AC-6.6).</li>
 *   <li><b>Registration:</b> {@link #acquire} with the IP key before the account is created, so every
 *       attempt counts, including those that end in {@code 409} (AC-1.8).</li>
 * </ul>
 *
 * <p>A key is blocked while its window has not ended and it has reached the limit of its scope;
 * {@code Retry-After} is the time until the window ends. {@link #acquire} is atomic, so its limit is
 * exact under concurrency. {@link #checkAllowed} only reads: attempts that pass it at the same time
 * are all counted afterwards, so a burst can exceed a login limit by the number of attempts in
 * flight, but never repeat it in the same window.
 */
@Service
public class AuthThrottleService {

    /** How long a counter is kept after its window has ended. */
    static final Duration RETENTION = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(AuthThrottleService.class);

    private final AuthThrottleRepository repository;
    private final ThrottleProperties properties;
    private final Clock clock;

    public AuthThrottleService(AuthThrottleRepository repository, ThrottleProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Rejects the attempt if any of the keys is blocked; does not count it.
     *
     * @throws TooManyAttemptsException with the longest remaining block of the blocked keys
     */
    public void checkAllowed(ThrottleKey... keys) {
        Instant now = clock.instant();
        Optional<Duration> retryAfter = Stream.of(keys)
                .flatMap(key -> repository.find(key.value()).flatMap(window -> blockedFor(window, limit(key), now)).stream())
                .max(Duration::compareTo);
        if (retryAfter.isPresent()) {
            throw new TooManyAttemptsException(retryAfter.get());
        }
    }

    /** Counts a failed attempt for each key. */
    public void recordFailure(ThrottleKey... keys) {
        Instant now = clock.instant();
        for (ThrottleKey key : keys) {
            record(key, now);
        }
    }

    /**
     * Counts the attempt and rejects it if it exceeds the limit of the current window (atomically).
     *
     * @throws TooManyAttemptsException if the limit is exceeded
     */
    public void acquire(ThrottleKey key) {
        Instant now = clock.instant();
        Limit limit = limit(key);
        ThrottleWindow window = record(key, now);
        if (window.attemptCount() > limit.maxAttempts()) {
            throw new TooManyAttemptsException(Duration.between(now, end(window, limit)));
        }
    }

    /** Forgets the counted attempts of the key, e.g. after a successful login. */
    public void reset(ThrottleKey key) {
        repository.delete(key.value());
    }

    /**
     * Deletes the counters whose window ended at least {@link #RETENTION} ago (measured with the longest
     * configured window), so the table only holds recent keys. Runs hourly; on several instances the
     * runs simply overlap.
     *
     * @return the number of deleted counters
     */
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public int deleteEndedWindows() {
        Duration longestWindow = Stream.of(ThrottleScope.values()).map(scope -> limit(scope).window())
                .max(Duration::compareTo).orElseThrow();
        int deleted = repository.deleteWindowsStartedBefore(clock.instant().minus(longestWindow).minus(RETENTION));
        log.debug("Deleted {} ended throttling windows", deleted);
        return deleted;
    }

    private ThrottleWindow record(ThrottleKey key, Instant now) {
        Limit limit = limit(key);
        // One more than the limit is enough to know that it was exceeded.
        return repository.recordAttempt(key.value(), now, limit.window(), limit.maxAttempts() + 1);
    }

    private static Optional<Duration> blockedFor(ThrottleWindow window, Limit limit, Instant now) {
        Instant end = end(window, limit);
        return window.attemptCount() >= limit.maxAttempts() && end.isAfter(now)
                ? Optional.of(Duration.between(now, end))
                : Optional.empty();
    }

    private static Instant end(ThrottleWindow window, Limit limit) {
        return window.windowStartedAt().plus(limit.window());
    }

    private Limit limit(ThrottleKey key) {
        return limit(key.scope());
    }

    private Limit limit(ThrottleScope scope) {
        return switch (scope) {
            case LOGIN_ACCOUNT_IP -> properties.loginAccountIp();
            case LOGIN_IP -> properties.loginIp();
            case REGISTER_IP -> properties.registerIp();
        };
    }
}
