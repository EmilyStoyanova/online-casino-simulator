package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Flyway schema in PostgreSQL itself (independent of JPA): defaults, required columns, constraints
 * and triggers of {@code users} (V3), and that the migrations apply to a database with existing data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationsIT {

    private static final String HASH = "{bcrypt}$2a$12$abcdefghijklmnopqrstuuJ0b0t3Zl0Q1zU0z9eXv7m8e3a1b2c3d4";
    private static final String INSERT = """
            INSERT INTO users (email, password_hash, display_name, adult_confirmed_at, role, status)
            VALUES (?, ?, ?, ?, ?, ?)""";
    private static final String COMPAT_SCHEMA = "migration_compat";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void deleteAccounts() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void usersGetDefaultsFromTheDatabase() {
        jdbcTemplate.update("INSERT INTO users (email, password_hash, display_name, adult_confirmed_at) VALUES (?, ?, ?, now())",
                "alice@example.com", HASH, "Alice");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE email = 'alice@example.com'");

        assertThat(row.get("id")).isNotNull();
        assertThat(row).containsEntry("role", "PLAYER").containsEntry("status", "ACTIVE").containsEntry("version", 0L);
        assertThat(row.get("created_at")).isNotNull().isEqualTo(row.get("updated_at"));
        assertThat(row.get("last_login_at")).isNull();
    }

    @Test
    void requiredColumnsAreNotNull() {
        Instant now = Instant.now();
        assertViolates("\"email\"", INSERT, null, HASH, "Alice", ts(now), "PLAYER", "ACTIVE");
        assertViolates("\"password_hash\"", INSERT, "a@example.com", null, "Alice", ts(now), "PLAYER", "ACTIVE");
        assertViolates("\"display_name\"", INSERT, "a@example.com", HASH, null, ts(now), "PLAYER", "ACTIVE");
        assertViolates("\"adult_confirmed_at\"", INSERT, "a@example.com", HASH, "Alice", null, "PLAYER", "ACTIVE");
        assertViolates("\"role\"", INSERT, "a@example.com", HASH, "Alice", ts(now), null, "ACTIVE");
        assertViolates("\"status\"", INSERT, "a@example.com", HASH, "Alice", ts(now), "PLAYER", null);
    }

    @Test
    void constraintsRejectInvalidValues() {
        Timestamp now = ts(Instant.now());
        jdbcTemplate.update(INSERT, "alice@example.com", HASH, "Alice", now, "PLAYER", "ACTIVE");

        assertViolates("uq_users_email", INSERT, "alice@example.com", HASH, "Alice 2", now, "PLAYER", "ACTIVE");
        assertViolates("ck_users_email_normalized", INSERT, "Bob@example.com", HASH, "Bob", now, "PLAYER", "ACTIVE");
        assertViolates("ck_users_email_normalized", INSERT, " bob@example.com", HASH, "Bob", now, "PLAYER", "ACTIVE");
        // A plain-text password cannot be stored by mistake: the encoder's {id} prefix is required.
        assertViolates("ck_users_password_hash_encoded", INSERT, "bob@example.com", "correct-horse-battery", "Bob", now,
                "PLAYER", "ACTIVE");
        assertViolates("ck_users_role", INSERT, "bob@example.com", HASH, "Bob", now, "ROOT", "ACTIVE");
        assertViolates("ck_users_status", INSERT, "bob@example.com", HASH, "Bob", now, "PLAYER", "LOCKED");
        assertViolates("character varying(254)", INSERT, "b".repeat(243) + "@example.com", HASH, "Bob", now,
                "PLAYER", "ACTIVE");
        assertViolates("character varying(30)", INSERT, "bob@example.com", HASH, "B".repeat(31), now, "PLAYER", "ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isOne();
    }

    @Test
    void updatesRefreshUpdatedAt() {
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at, created_at, updated_at)
                VALUES ('alice@example.com', ?, 'Alice', now(), '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')""", HASH);

        jdbcTemplate.update("UPDATE users SET display_name = 'Alice B.' WHERE email = 'alice@example.com'");

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT updated_at > TIMESTAMPTZ '2026-01-01T00:00:00Z' AS refreshed,
                       created_at = TIMESTAMPTZ '2026-01-01T00:00:00Z' AS created_kept
                FROM users WHERE email = 'alice@example.com'""");
        assertThat(row).containsEntry("refreshed", Boolean.TRUE).containsEntry("created_kept", Boolean.TRUE);
    }

    @Test
    void migrationsApplyToADatabaseWithExistingData() {
        // A separate schema at V2 (the state of databases before W2-05), with a stored session.
        Flyway atV2 = flyway("2");
        Flyway latest = flyway(null);
        try {
            atV2.migrate();
            jdbcTemplate.update("INSERT INTO " + COMPAT_SCHEMA + ".spring_session "
                    + "(primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time) "
                    + "VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 1, 1, 1800, 2)");

            latest.migrate();

            // Versioned migrations only (Flyway also records the creation of the schema, without a version).
            assertThat(latest.info().applied()).filteredOn(info -> info.getVersion() != null)
                    .allMatch(info -> info.getState().isApplied() && !info.getState().isFailed())
                    .extracting(info -> info.getVersion().getVersion())
                    .containsExactly("1", "2", "3");
            // Existing data is untouched, and the new table is there.
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COMPAT_SCHEMA + ".spring_session", Integer.class))
                    .isOne();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COMPAT_SCHEMA + ".users", Integer.class))
                    .isZero();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + COMPAT_SCHEMA + " CASCADE");
        }
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(COMPAT_SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void assertViolates(String expectedInMessage, String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
                .as(expectedInMessage)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(expectedInMessage);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
