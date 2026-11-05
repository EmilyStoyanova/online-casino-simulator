package com.casino.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.casino.Concurrency;
import com.casino.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link AuthThrottleRepository} against PostgreSQL (Testcontainers) with the Flyway schema (V4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthThrottleRepositoryIT {

    private static final String KEY = "LOGIN_IP:" + "a".repeat(64);
    private static final String OTHER_KEY = "LOGIN_IP:" + "b".repeat(64);
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CEILING = 1_000;
    // PostgreSQL stores microseconds.
    private static final Instant START = Instant.parse("2026-11-02T10:00:00.123456Z");

    @Autowired
    private AuthThrottleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void deleteCounters() {
        jdbcTemplate.update("DELETE FROM auth_throttle");
    }

    @Test
    void firstAttemptStartsAWindow() {
        assertThat(repository.find(KEY)).isEmpty();

        ThrottleWindow window = repository.recordAttempt(KEY, START, WINDOW, CEILING);

        assertThat(window).isEqualTo(new ThrottleWindow(1, START));
        assertThat(repository.find(KEY)).contains(window);
    }

    @Test
    void attemptsWithinTheWindowAreCounted() {
        repository.recordAttempt(KEY, START, WINDOW, CEILING);
        repository.recordAttempt(KEY, START.plusSeconds(60), WINDOW, CEILING);
        ThrottleWindow window = repository.recordAttempt(KEY, START.plus(WINDOW).minusNanos(1_000), WINDOW, CEILING);

        // The window keeps its start; it is fixed, not sliding.
        assertThat(window).isEqualTo(new ThrottleWindow(3, START));
        // Other keys are independent.
        assertThat(repository.recordAttempt(OTHER_KEY, START, WINDOW, CEILING).attemptCount()).isOne();
    }

    @Test
    void anAttemptAfterTheWindowEndedStartsANewOne() {
        repository.recordAttempt(KEY, START, WINDOW, CEILING);
        repository.recordAttempt(KEY, START.plusSeconds(1), WINDOW, CEILING);

        // The window ends exactly at start + window.
        Instant end = START.plus(WINDOW);
        assertThat(repository.recordAttempt(KEY, end, WINDOW, CEILING)).isEqualTo(new ThrottleWindow(1, end));
        assertThat(repository.recordAttempt(KEY, end.plusSeconds(1), WINDOW, CEILING)).isEqualTo(new ThrottleWindow(2, end));
    }

    @Test
    void theCounterStopsAtTheCeiling() {
        for (int i = 0; i < 10; i++) {
            repository.recordAttempt(KEY, START, WINDOW, 6);
        }

        assertThat(repository.find(KEY)).contains(new ThrottleWindow(6, START));
    }

    @Test
    void concurrentAttemptsAreEachCountedExactlyOnce() throws Exception {
        int attempts = 40;
        // All threads start together; the first insert of the key races with the updates.
        List<ThrottleWindow> results = Concurrency.runTogether(attempts,() -> repository.recordAttempt(KEY, START, WINDOW, CEILING));

        assertThat(repository.find(KEY)).contains(new ThrottleWindow(attempts, START));
        // Every attempt saw a different count: none was lost or applied twice.
        assertThat(results).extracting(ThrottleWindow::attemptCount).doesNotHaveDuplicates()
                .allMatch(count -> count >= 1 && count <= attempts);
    }

    @Test
    void deletesOneCounter() {
        repository.recordAttempt(KEY, START, WINDOW, CEILING);
        repository.recordAttempt(OTHER_KEY, START, WINDOW, CEILING);

        repository.delete(KEY);
        repository.delete(KEY);

        assertThat(repository.find(KEY)).isEmpty();
        assertThat(repository.find(OTHER_KEY)).isPresent();
    }

    @Test
    void deletesCountersWhoseWindowStartedBeforeTheCutoff() {
        repository.recordAttempt(KEY, START, WINDOW, CEILING);
        repository.recordAttempt(OTHER_KEY, START.plusSeconds(1), WINDOW, CEILING);

        assertThat(repository.deleteWindowsStartedBefore(START)).isZero();
        assertThat(repository.deleteWindowsStartedBefore(START.plusSeconds(1))).isOne();

        assertThat(repository.find(KEY)).isEmpty();
        assertThat(repository.find(OTHER_KEY)).isPresent();
    }
}
