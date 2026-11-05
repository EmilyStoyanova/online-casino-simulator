package com.casino.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.casino.auth.config.ThrottleProperties;
import com.casino.auth.config.ThrottleProperties.Limit;
import com.casino.auth.exception.TooManyAttemptsException;
import com.casino.auth.repository.AuthThrottleRepository;
import com.casino.auth.repository.ThrottleWindow;
import org.junit.jupiter.api.Test;

/** Decisions of {@link AuthThrottleService} with a fixed clock; the counting itself is in the repository. */
class AuthThrottleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final ThrottleProperties PROPERTIES = new ThrottleProperties(
            new Limit(5, Duration.ofMinutes(15)), new Limit(50, Duration.ofMinutes(10)), new Limit(10, Duration.ofHours(1)),
            null, true);
    private static final ThrottleKeys KEYS = new ThrottleKeys(PROPERTIES);
    private static final ThrottleKey ACCOUNT_IP = KEYS.loginAccountIp("alice@example.com", "203.0.113.7");
    private static final ThrottleKey IP = KEYS.loginIp("203.0.113.7");
    private static final ThrottleKey REGISTER = KEYS.registerIp("203.0.113.7");

    private final AuthThrottleRepository repository = mock(AuthThrottleRepository.class);
    private final AuthThrottleService service = new AuthThrottleService(repository, PROPERTIES, new MutableClock(NOW));

    @Test
    void keysWithoutCounterOrBelowTheLimitAreAllowed() {
        when(repository.find(anyString())).thenReturn(Optional.empty());
        when(repository.find(ACCOUNT_IP.value())).thenReturn(window(4, NOW.minusSeconds(60)));

        assertThatCode(() -> service.checkAllowed(ACCOUNT_IP, IP)).doesNotThrowAnyException();
    }

    @Test
    void aKeyAtItsLimitIsBlockedUntilTheWindowEnds() {
        when(repository.find(ACCOUNT_IP.value())).thenReturn(window(5, NOW.minusSeconds(60)));

        assertThatThrownBy(() -> service.checkAllowed(ACCOUNT_IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(14)));
    }

    @Test
    void eachScopeUsesItsOwnLimitAndWindow() {
        // 5 is the account + IP limit, not the IP limit.
        when(repository.find(IP.value())).thenReturn(window(5, NOW));
        assertThatCode(() -> service.checkAllowed(IP)).doesNotThrowAnyException();

        when(repository.find(IP.value())).thenReturn(window(50, NOW.minusSeconds(60)));
        assertThatThrownBy(() -> service.checkAllowed(IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(9)));
    }

    @Test
    void aCounterOfAnEndedWindowNoLongerBlocks() {
        when(repository.find(ACCOUNT_IP.value())).thenReturn(window(5, NOW.minus(Duration.ofMinutes(15))));

        assertThatCode(() -> service.checkAllowed(ACCOUNT_IP)).doesNotThrowAnyException();
    }

    @Test
    void failuresAreCountedForEveryKeyWithItsWindowAndABoundedCounter() {
        service.recordFailure(ACCOUNT_IP, IP);

        verify(repository).recordAttempt(ACCOUNT_IP.value(), NOW, Duration.ofMinutes(15), 6);
        verify(repository).recordAttempt(IP.value(), NOW, Duration.ofMinutes(10), 51);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void acquireRejectsTheFirstAttemptOverTheLimit() {
        when(repository.recordAttempt(REGISTER.value(), NOW, Duration.ofHours(1), 11))
                .thenReturn(new ThrottleWindow(10, NOW.minusSeconds(600)))
                .thenReturn(new ThrottleWindow(11, NOW.minusSeconds(600)));

        assertThatCode(() -> service.acquire(REGISTER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.acquire(REGISTER))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(50)));
    }

    @Test
    void resetDeletesTheCounter() {
        service.reset(ACCOUNT_IP);

        verify(repository).delete(ACCOUNT_IP.value());
    }

    @Test
    void cleanupKeepsCountersForADayAfterTheLongestWindow() {
        when(repository.deleteWindowsStartedBefore(NOW.minus(Duration.ofHours(1)).minus(Duration.ofDays(1)))).thenReturn(3);

        assertThat(service.deleteEndedWindows()).isEqualTo(3);
    }

    private static Optional<ThrottleWindow> window(int attempts, Instant startedAt) {
        return Optional.of(new ThrottleWindow(attempts, startedAt));
    }
}
