package com.casino.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.casino.Concurrency;
import com.casino.Concurrency.Outcome;
import com.casino.TestcontainersConfiguration;
import com.casino.auth.config.ThrottleProperties;
import com.casino.auth.exception.TooManyAttemptsException;
import com.casino.auth.repository.AuthThrottleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AuthThrottleService} with the configured limits ({@code application.yml}) against PostgreSQL:
 * thresholds per scope, expiration of windows, concurrent attempts and cleanup. The service is created
 * with a {@link MutableClock}, so windows can end without waiting.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthThrottleServiceIT {

    private static final String EMAIL = "alice@example.com";
    private static final String IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.23";
    private static final Instant START = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired
    private AuthThrottleRepository repository;

    @Autowired
    private ThrottleProperties properties;

    @Autowired
    private ThrottleKeys keys;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MutableClock clock;
    private AuthThrottleService service;

    @BeforeEach
    void createService() {
        clock = new MutableClock(START);
        service = new AuthThrottleService(repository, properties, clock);
    }

    @AfterEach
    void deleteCounters() {
        jdbcTemplate.update("DELETE FROM auth_throttle");
    }

    @Test
    void usesThePlannedLimits() {
        assertThat(properties.loginAccountIp()).isEqualTo(new ThrottleProperties.Limit(5, Duration.ofMinutes(15)));
        assertThat(properties.loginIp()).isEqualTo(new ThrottleProperties.Limit(50, Duration.ofMinutes(15)));
        assertThat(properties.registerIp()).isEqualTo(new ThrottleProperties.Limit(10, Duration.ofHours(1)));
    }

    @Test
    void fiveFailedLoginsBlockTheAccountFromThatIpUntilTheWindowEnds() {
        ThrottleKey accountIp = keys.loginAccountIp(EMAIL, IP);
        for (int i = 0; i < 4; i++) {
            failLogin(EMAIL, IP);
            clock.advance(Duration.ofMinutes(1));
        }
        assertThatCode(() -> service.checkAllowed(accountIp)).doesNotThrowAnyException();
        failLogin(EMAIL, IP);

        // AC-6.1: blocked until the end of the window that began with the first failure.
        assertRetryAfter(() -> service.checkAllowed(accountIp, keys.loginIp(IP)), Duration.ofMinutes(11));
        // AC-6.2: no account lockout; the same account from another IP is not affected.
        assertThatCode(() -> service.checkAllowed(keys.loginAccountIp(EMAIL, OTHER_IP), keys.loginIp(OTHER_IP)))
                .doesNotThrowAnyException();

        // Expiration: one microsecond before the end still blocked, at the end allowed again.
        clock.advance(Duration.ofMinutes(11).minusNanos(1_000));
        assertRetryAfter(() -> service.checkAllowed(accountIp), Duration.ofNanos(1_000));
        clock.advance(Duration.ofNanos(1_000));
        assertThatCode(() -> service.checkAllowed(accountIp)).doesNotThrowAnyException();
        // The next failure starts a new window with a fresh count.
        failLogin(EMAIL, IP);
        assertThat(repository.find(accountIp.value()).orElseThrow().attemptCount()).isOne();
    }

    @Test
    void caseVariantsOfAnEmailShareOneCounter() {
        for (String email : List.of("alice@example.com", "Alice@Example.com", " ALICE@example.com ", "alice@EXAMPLE.com",
                "aLiCe@example.com")) {
            failLogin(email, IP);
        }

        assertThatThrownBy(() -> service.checkAllowed(keys.loginAccountIp(EMAIL, IP)))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void fiftyFailedLoginsFromOneIpBlockThatIpForEveryAccount() {
        // AC-6.3: credential stuffing; 50 accounts with one failure each.
        for (int i = 0; i < 50; i++) {
            String email = "user" + i + "@example.com";
            assertThatCode(() -> service.checkAllowed(keys.loginAccountIp(email, IP), keys.loginIp(IP)))
                    .doesNotThrowAnyException();
            failLogin(email, IP);
        }

        // An account never tried before is blocked from that IP as well, but not from another IP.
        assertRetryAfter(() -> service.checkAllowed(keys.loginAccountIp("new@example.com", IP), keys.loginIp(IP)),
                Duration.ofMinutes(15));
        assertThatCode(() -> service.checkAllowed(keys.loginAccountIp("new@example.com", OTHER_IP),
                keys.loginIp(OTHER_IP))).doesNotThrowAnyException();
    }

    @Test
    void theLongestRemainingBlockIsReported() {
        for (int i = 0; i < 49; i++) {
            failLogin("user" + i + "@example.com", IP);
        }
        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 5; i++) {
            failLogin(EMAIL, IP);
        }

        // IP window: started at START, 10 min left; account + IP window: started 5 min later, 15 min left.
        assertRetryAfter(() -> service.checkAllowed(keys.loginAccountIp(EMAIL, IP), keys.loginIp(IP)),
                Duration.ofMinutes(15));
    }

    @Test
    void aSuccessfulLoginResetsOnlyTheAccountAndIpCounter() {
        for (int i = 0; i < 4; i++) {
            failLogin(EMAIL, IP);
        }

        // AC-6.6
        service.reset(keys.loginAccountIp(EMAIL, IP));

        assertThat(repository.find(keys.loginAccountIp(EMAIL, IP).value())).isEmpty();
        assertThat(repository.find(keys.loginIp(IP).value()).orElseThrow().attemptCount()).isEqualTo(4);
        failLogin(EMAIL, IP);
        assertThatCode(() -> service.checkAllowed(keys.loginAccountIp(EMAIL, IP))).doesNotThrowAnyException();
    }

    @Test
    void tenRegistrationAttemptsPerIpAndHourAreAllowed() {
        ThrottleKey registerIp = keys.registerIp(IP);
        for (int i = 0; i < 10; i++) {
            service.acquire(registerIp);
            clock.advance(Duration.ofMinutes(1));
        }

        // AC-1.8: the 11th attempt within the hour is rejected; rejected attempts do not extend the window.
        assertRetryAfter(() -> service.acquire(registerIp), Duration.ofMinutes(50));
        assertRetryAfter(() -> service.acquire(registerIp), Duration.ofMinutes(50));
        assertThatCode(() -> service.acquire(keys.registerIp(OTHER_IP))).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(50));
        assertThatCode(() -> service.acquire(registerIp)).doesNotThrowAnyException();
    }

    @Test
    void concurrentRegistrationAttemptsNeverExceedTheLimit() throws Exception {
        List<Outcome<Boolean>> outcomes = Concurrency.outcomes(30, () -> {
            service.acquire(keys.registerIp(IP));
            return true;
        });

        assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).hasSize(10);
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null)
                .hasSize(20)
                .allMatch(outcome -> outcome.failure() instanceof TooManyAttemptsException);
        // The counter is bounded: it stops one above the limit.
        assertThat(repository.find(keys.registerIp(IP).value()).orElseThrow().attemptCount()).isEqualTo(11);
    }

    @Test
    void concurrentFailedLoginsAreCountedExactly() throws Exception {
        Concurrency.runTogether(20, () -> {
            service.recordFailure(keys.loginIp(IP));
            return null;
        });

        assertThat(repository.find(keys.loginIp(IP).value()).orElseThrow().attemptCount()).isEqualTo(20);
    }

    @Test
    void storedKeysContainNoEmailOrIpAddress() {
        failLogin(EMAIL, IP);
        service.acquire(keys.registerIp(IP));

        List<String> keys = jdbcTemplate.queryForList("SELECT throttle_key FROM auth_throttle", String.class);

        assertThat(keys).hasSize(3).allMatch(key -> key.matches("[A-Z_]+:[0-9a-f]{64}"))
                .noneMatch(key -> key.contains("alice") || key.contains("example") || key.contains(IP));
    }

    @Test
    void cleanupDeletesCountersADayAfterTheirWindowEnded() {
        failLogin(EMAIL, IP);
        clock.advance(Duration.ofMinutes(30));
        service.acquire(keys.registerIp(IP));

        // Longest window (1 h) + retention (1 day) after the first counters began.
        clock.advance(Duration.ofHours(1).plus(AuthThrottleService.RETENTION).minusMinutes(30));
        assertThat(service.deleteEndedWindows()).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.deleteEndedWindows()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT throttle_key FROM auth_throttle", String.class))
                .containsExactly(keys.registerIp(IP).value());

        clock.advance(Duration.ofMinutes(30));
        assertThat(service.deleteEndedWindows()).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM auth_throttle", Integer.class)).isZero();
    }

    /** What the login service does after a failed password check (W2-08). */
    private void failLogin(String email, String ip) {
        service.recordFailure(keys.loginAccountIp(email, ip), keys.loginIp(ip));
    }

    private static void assertRetryAfter(Runnable attempt, Duration expected) {
        assertThatThrownBy(attempt::run)
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(expected));
    }
}
