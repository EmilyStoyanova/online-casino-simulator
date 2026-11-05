package com.casino.auth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Fixed-window counters in {@code auth_throttle} (plain JDBC; {@code auth} has no entities). The
 * current time is passed in by the caller, so the service's {@code Clock} decides when a window ends.
 */
@Repository
public class AuthThrottleRepository {

    /*
     * One atomic statement: insert a new counter, or, if the key exists, start a new window when the old
     * one has ended or increment the counter. PostgreSQL locks the row for ON CONFLICT DO UPDATE, so
     * concurrent attempts on the same key are applied one after another and each is counted exactly once.
     */
    private static final String RECORD_ATTEMPT = """
            INSERT INTO auth_throttle AS t (throttle_key, attempt_count, window_started_at)
            VALUES (:key, 1, :now)
            ON CONFLICT (throttle_key) DO UPDATE SET
                attempt_count     = CASE WHEN t.window_started_at <= :endedBefore THEN 1
                                         ELSE LEAST(t.attempt_count + 1, :ceiling) END,
                window_started_at = CASE WHEN t.window_started_at <= :endedBefore THEN EXCLUDED.window_started_at
                                         ELSE t.window_started_at END
            RETURNING attempt_count, window_started_at""";

    private final JdbcClient jdbc;

    public AuthThrottleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Counts one attempt for {@code key}: a new window starting at {@code now} if there is none or the
     * previous one ({@code window} long) has ended, otherwise the next attempt of the current window.
     * The counter stops at {@code ceiling}, so it stays bounded however many attempts are made.
     *
     * @return the window after this attempt
     */
    public ThrottleWindow recordAttempt(String key, Instant now, Duration window, int ceiling) {
        return jdbc.sql(RECORD_ATTEMPT)
                .param("key", key)
                .param("now", utc(now))
                .param("endedBefore", utc(now.minus(window)))
                .param("ceiling", ceiling)
                .query(AuthThrottleRepository::window)
                .single();
    }

    public Optional<ThrottleWindow> find(String key) {
        return jdbc.sql("SELECT attempt_count, window_started_at FROM auth_throttle WHERE throttle_key = :key")
                .param("key", key)
                .query(AuthThrottleRepository::window)
                .optional();
    }

    public void delete(String key) {
        jdbc.sql("DELETE FROM auth_throttle WHERE throttle_key = :key").param("key", key).update();
    }

    /** Deletes the counters whose window started before {@code cutoff}; returns how many. */
    public int deleteWindowsStartedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM auth_throttle WHERE window_started_at < :cutoff").param("cutoff", utc(cutoff)).update();
    }

    private static ThrottleWindow window(ResultSet rs, int rowNum) throws SQLException {
        return new ThrottleWindow(rs.getInt("attempt_count"),
                rs.getObject("window_started_at", OffsetDateTime.class).toInstant());
    }

    /** {@code timestamptz} parameter: JDBC 4.2 maps it to {@link OffsetDateTime}. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
