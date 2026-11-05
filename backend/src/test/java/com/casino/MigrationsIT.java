package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * and triggers of {@code users} (V3), {@code auth_throttle} (V4) and {@code interests} /
 * {@code user_interests} with the seeded catalogue (V5), and that the migrations apply to a database
 * with existing data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationsIT {

    private static final String HASH = "{bcrypt}$2a$12$abcdefghijklmnopqrstuuJ0b0t3Zl0Q1zU0z9eXv7m8e3a1b2c3d4";
    private static final String INSERT = """
            INSERT INTO users (email, password_hash, display_name, adult_confirmed_at, role, status)
            VALUES (?, ?, ?, ?, ?, ?)""";
    private static final String THROTTLE_INSERT =
            "INSERT INTO auth_throttle (throttle_key, attempt_count, window_started_at) VALUES (?, ?, ?)";
    private static final String INTEREST_INSERT =
            "INSERT INTO interests (code, name, description, sort_order) VALUES (?, ?, ?, ?)";
    private static final String USER_INTEREST_INSERT = "INSERT INTO user_interests (user_id, interest_code) VALUES (?, ?)";
    /** The catalogue seeded by V5, in display order. */
    private static final List<String> SEEDED_INTERESTS = List.of("PROBABILITY", "MATHEMATICS", "STATISTICS", "GAME_THEORY",
            "PSYCHOLOGY", "FINANCIAL_LITERACY", "HISTORY", "RESPONSIBLE_PLAY");
    private static final String COMPAT_SCHEMA = "migration_compat";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void deleteRows() {
        // Deletes the users' interests too (ON DELETE CASCADE); the seeded catalogue stays.
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM auth_throttle");
        jdbcTemplate.update("DELETE FROM interests WHERE code LIKE 'TEST\\_%'");
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
    void throttleCountersAcceptOnlyHashedKeysAndPositiveCounts() {
        String key = "LOGIN_ACCOUNT_IP:" + "0123456789abcdef".repeat(4);
        jdbcTemplate.update(THROTTLE_INSERT, key, 1, ts(Instant.now()));

        assertViolates("auth_throttle_pkey", THROTTLE_INSERT, key, 2, ts(Instant.now()));
        assertViolates("\"attempt_count\"", THROTTLE_INSERT, "LOGIN_IP:" + "a".repeat(64), null, ts(Instant.now()));
        assertViolates("\"window_started_at\"", THROTTLE_INSERT, "LOGIN_IP:" + "a".repeat(64), 1, null);
        assertViolates("ck_auth_throttle_attempt_count", THROTTLE_INSERT, "LOGIN_IP:" + "a".repeat(64), 0, ts(Instant.now()));
        // Raw personal data or malformed keys cannot be stored by mistake.
        for (String rawKey : List.of("LOGIN_ACCOUNT_IP:alice@example.com|203.0.113.7", "LOGIN_IP:203.0.113.7",
                "login_ip:" + "a".repeat(64), "LOGIN_IP:" + "A".repeat(64), "LOGIN_IP:" + "a".repeat(63), "a".repeat(64))) {
            assertViolates("ck_auth_throttle_key_hashed", THROTTLE_INSERT, rawKey, 1, ts(Instant.now()));
        }
        assertViolates("character varying(90)", THROTTLE_INSERT, "X".repeat(26) + ":" + "a".repeat(64), 1, ts(Instant.now()));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM auth_throttle", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'auth_throttle'", String.class))
                .containsExactlyInAnyOrder("auth_throttle_pkey", "ix_auth_throttle_window_started_at");
    }

    @Test
    void theInterestCatalogueIsSeededDeterministically() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT code, name, description, sort_order, active FROM interests ORDER BY sort_order, code");

        assertThat(rows).extracting(row -> row.get("code")).containsExactlyElementsOf(SEEDED_INTERESTS);
        // Fixed, distinct display orders and all active.
        assertThat(rows).extracting(row -> ((Number) row.get("sort_order")).intValue())
                .containsExactly(10, 20, 30, 40, 50, 60, 70, 80);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("active", Boolean.TRUE));
        assertThat(rows.getFirst()).containsEntry("name", "Probability & odds")
                .containsEntry("description", "How likely outcomes are and how odds are expressed");
        // The escaped quote of the seed script.
        assertThat(jdbcTemplate.queryForObject("SELECT description FROM interests WHERE code = 'PSYCHOLOGY'", String.class))
                .isEqualTo("Cognitive biases such as the gambler's fallacy");
    }

    @Test
    void interestConstraintsRejectInvalidValues() {
        jdbcTemplate.update(INTEREST_INSERT, "TEST_TOPIC", "Test topic", "For this test", 90);
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM interests WHERE code = 'TEST_TOPIC'", Boolean.class)).isTrue();

        assertViolates("interests_pkey", INTEREST_INSERT, "PROBABILITY", "Again", "Duplicate code", 91);
        // Codes are stable upper-case identifiers, as the contract's pattern requires.
        for (String code : List.of("test_lower", "1TEST", "TEST-DASH", "TEST SPACE", "", "_TEST")) {
            assertViolates("ck_interests_code", INTEREST_INSERT, code, "Name", "Description", 92);
        }
        assertViolates("\"code\"", INTEREST_INSERT, null, "Name", "Description", 92);
        assertViolates("\"name\"", INTEREST_INSERT, "TEST_NO_NAME", null, "Description", 92);
        assertViolates("\"description\"", INTEREST_INSERT, "TEST_NO_DESCRIPTION", "Name", null, 92);
        assertViolates("\"sort_order\"", INTEREST_INSERT, "TEST_NO_ORDER", "Name", "Description", null);
        assertViolates("character varying(40)", INTEREST_INSERT, "TEST_" + "X".repeat(36), "Name", "Description", 92);
        assertViolates("character varying(80)", INTEREST_INSERT, "TEST_LONG_NAME", "N".repeat(81), "Description", 92);
        assertViolates("character varying(255)", INTEREST_INSERT, "TEST_LONG_DESCRIPTION", "Name", "D".repeat(256), 92);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM interests", Integer.class))
                .isEqualTo(SEEDED_INTERESTS.size() + 1);
    }

    @Test
    void userInterestsRejectDuplicateAndInvalidAssociations() {
        UUID alice = insertUser("alice@example.com");
        jdbcTemplate.update(USER_INTEREST_INSERT, alice, "PROBABILITY");
        assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM user_interests WHERE user_id = ?", Timestamp.class, alice))
                .isNotNull();

        assertViolates("user_interests_pkey", USER_INTEREST_INSERT, alice, "PROBABILITY");
        assertViolates("user_interests_interest_code_fkey", USER_INTEREST_INSERT, alice, "UNKNOWN");
        assertViolates("user_interests_user_id_fkey", USER_INTEREST_INSERT, UUID.randomUUID(), "MATHEMATICS");
        assertViolates("\"user_id\"", USER_INTEREST_INSERT, null, "MATHEMATICS");
        assertViolates("\"interest_code\"", USER_INTEREST_INSERT, alice, null);
        // Several distinct interests per user, and the same interest for several users.
        jdbcTemplate.update(USER_INTEREST_INSERT, alice, "MATHEMATICS");
        jdbcTemplate.update(USER_INTEREST_INSERT, insertUser("bob@example.com"), "PROBABILITY");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_interests", Integer.class)).isEqualTo(3);
    }

    @Test
    void deletingAUserDeletesTheSelectionButASelectedInterestCannotBeDeleted() {
        UUID alice = insertUser("alice@example.com");
        UUID bob = insertUser("bob@example.com");
        jdbcTemplate.update(USER_INTEREST_INSERT, alice, "PROBABILITY");
        jdbcTemplate.update(USER_INTEREST_INSERT, bob, "STATISTICS");

        // Interests are deactivated, not deleted, while users reference them.
        assertViolates("user_interests_interest_code_fkey", "DELETE FROM interests WHERE code = ?", "PROBABILITY");

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", alice);
        assertThat(jdbcTemplate.queryForList("SELECT interest_code FROM user_interests", String.class))
                .containsExactly("STATISTICS");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM interests", Integer.class)).isEqualTo(SEEDED_INTERESTS.size());
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
                    .containsExactly("1", "2", "3", "4", "5");
            // Existing data is untouched, and the new tables are there.
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COMPAT_SCHEMA + ".spring_session", Integer.class))
                    .isOne();
            for (String table : List.of("users", "auth_throttle", "user_interests")) {
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COMPAT_SCHEMA + "." + table, Integer.class))
                        .as(table)
                        .isZero();
            }
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COMPAT_SCHEMA + ".interests", Integer.class))
                    .isEqualTo(SEEDED_INTERESTS.size());
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + COMPAT_SCHEMA + " CASCADE");
        }
    }

    @Test
    void v5KeepsExistingUsersSessionsAndThrottleCounters() {
        // A separate schema at V4 (the state of databases after W2-08) with an account, its session and a counter.
        Flyway atV4 = flyway("4");
        try {
            atV4.migrate();
            UUID alice = jdbcTemplate.queryForObject("INSERT INTO " + COMPAT_SCHEMA + ".users "
                    + "(email, password_hash, display_name, adult_confirmed_at) VALUES ('alice@example.com', ?, 'Alice', now()) "
                    + "RETURNING id", UUID.class, HASH);
            jdbcTemplate.update("INSERT INTO " + COMPAT_SCHEMA + ".spring_session "
                    + "(primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) "
                    + "VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 1, 1, 1800, 2, ?)",
                    alice.toString());
            jdbcTemplate.update("INSERT INTO " + COMPAT_SCHEMA + ".auth_throttle (throttle_key, attempt_count, window_started_at) "
                    + "VALUES (?, 3, now())", "LOGIN_IP:" + "a".repeat(64));
            Map<String, Object> before = jdbcTemplate.queryForMap("SELECT * FROM " + COMPAT_SCHEMA + ".users");

            flyway(null).migrate();

            assertThat(jdbcTemplate.queryForMap("SELECT * FROM " + COMPAT_SCHEMA + ".users")).isEqualTo(before);
            assertThat(jdbcTemplate.queryForObject("SELECT principal_name FROM " + COMPAT_SCHEMA + ".spring_session", String.class))
                    .isEqualTo(alice.toString());
            assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM " + COMPAT_SCHEMA + ".auth_throttle", Integer.class))
                    .isEqualTo(3);
            // The existing account starts without interests and can be given some (W2-10).
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COMPAT_SCHEMA + ".user_interests", Integer.class))
                    .isZero();
            jdbcTemplate.update("INSERT INTO " + COMPAT_SCHEMA + ".user_interests (user_id, interest_code) VALUES (?, 'HISTORY')", alice);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COMPAT_SCHEMA + ".user_interests", Integer.class))
                    .isOne();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + COMPAT_SCHEMA + " CASCADE");
        }
    }

    private UUID insertUser(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at) VALUES (?, ?, ?, now())
                RETURNING id""", UUID.class, email, HASH, email.substring(0, email.indexOf('@')));
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
