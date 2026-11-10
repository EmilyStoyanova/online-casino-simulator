package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.casino.wallet.repository.WalletRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Flyway migrations and the wallet schema (V6) on PostgreSQL 17 (Docker Compose, Testcontainers,
 * CI) **and** 18 (Neon runs 18.6; Render applies migrations at start-up, so a migration that only
 * works on 17 would break the next staging start). No Spring context.
 *
 * <p>Each test migrates its own new schema in a container started by this class, so no test cleans
 * up anything: the containers are discarded at the end. Violations are identified by constraint name
 * and SQLSTATE, never by message text, which differs between the versions (e.g. foreign keys).
 */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = {TestcontainersConfiguration.POSTGRES_IMAGE, MigrationCompatibilityIT.POSTGRES_18_IMAGE})
class MigrationCompatibilityIT {

    /** Same major and minor version as the Neon project. */
    static final String POSTGRES_18_IMAGE = "postgres:18.6-alpine";

    private static final String HASH = "{bcrypt}$2a$12$abcdefghijklmnopqrstuuJ0b0t3Zl0Q1zU0z9eXv7m8e3a1b2c3d4";
    private static final String INTEGRITY_VIOLATION = "23000";
    private static final Map<String, PostgreSQLContainer> CONTAINERS = new HashMap<>();
    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    @Parameter
    private String image;

    @AfterAll
    static void stopContainers() {
        CONTAINERS.values().forEach(PostgreSQLContainer::stop);
        CONTAINERS.clear();
    }

    @Test
    void allMigrationsApplyAndTheServerHasTheExpectedMajorVersion() throws SQLException {
        String schema = migrated(null);

        try (Connection connection = connect(schema)) {
            assertThat(queryString(connection, "SHOW server_version")).startsWith(image.substring("postgres:".length(), image.indexOf('.')));
            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success"))
                    .isEqualTo("6");
        }
    }

    @Test
    void walletsAreUniquePerExistingUserAndNeverNegativeOrAboveTheCap() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            UUID user = insertUser(connection, "alice@example.com");
            UUID wallet = insertWallet(connection, user, 0);
            assertThat(queryString(connection, "SELECT created_at = updated_at FROM wallets")).isEqualTo("t");

            assertViolation(connection, "INSERT INTO wallets (user_id, balance) VALUES ('" + user + "', 0)", "uq_wallets_user_id");
            assertViolation(connection, "INSERT INTO wallets (user_id, balance) VALUES ('" + UUID.randomUUID() + "', 0)",
                    "fk_wallets_user");
            assertViolation(connection, "UPDATE wallets SET balance = -1", "ck_wallets_balance");
            assertViolation(connection, "UPDATE wallets SET balance = 9007199254740992", "ck_wallets_balance");
            execute(connection, "UPDATE wallets SET balance = 9007199254740991");
            assertViolation(connection, "INSERT INTO wallets (user_id) VALUES ('" + insertUser(connection, "b@example.com") + "')", null);
            // The owner is fixed; the balance change refreshed updated_at.
            assertTriggerRejects(connection, "UPDATE wallets SET user_id = '" + insertUser(connection, "c@example.com") + "'",
                    "wallets.user_id cannot change");
            assertThat(queryString(connection, "SELECT updated_at > created_at FROM wallets WHERE id = '" + wallet + "'"))
                    .isEqualTo("t");
        }
    }

    @Test
    void ledgerEntriesFollowTheirRules() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            UUID wallet = insertWallet(connection, insertUser(connection, "dave@example.com"), 1000);
            insertEntry(connection, wallet, "STARTING_BALANCE", 1000, 1000, "starting-balance");

            assertViolation(connection, entrySql(wallet, "STARTING_BALANCE", 1, 1001, "starting-balance"),
                    "uq_wallet_transactions_idempotency");
            assertViolation(connection, entrySql(wallet, "STARTING_BALANCE", 0, 1000, "zero"), "ck_wallet_transactions_amount");
            assertViolation(connection, entrySql(wallet, "STARTING_BALANCE", 9007199254740992L, 1000, "too-big"),
                    "ck_wallet_transactions_amount");
            assertViolation(connection, entrySql(wallet, "STARTING_BALANCE", 5, -1, "negative"), "ck_wallet_transactions_balance");
            assertViolation(connection, entrySql(wallet, "STARTING_BALANCE", 5, 9007199254740992L, "above-cap"),
                    "ck_wallet_transactions_balance");
            assertViolation(connection, entrySql(wallet, "ADMIN_GIFT", 5, 1005, "unknown-type"), "ck_wallet_transactions_type");
            // The test-only types exist only in test databases (callback db/test-callbacks/afterMigrate.sql,
            // which this class does not load): the production schema rejects them.
            assertViolation(connection, entrySql(wallet, "TEST_CREDIT", 5, 1005, "test-credit"), "ck_wallet_transactions_type");
            assertViolation(connection, entrySql(wallet, "TEST_DEBIT", -5, 995, "test-debit"), "ck_wallet_transactions_type");
            assertViolation(connection, entrySql(wallet, "STARTING_BALANCE", -5, 995, "wrong-direction"),
                    "ck_wallet_transactions_direction");
            for (String key : new String[] {"Upper-Case", "with space", "-leading-dash", "semi;colon", ""}) {
                assertViolation(connection, entrySql(wallet, "STARTING_BALANCE", 5, 1005, key), "ck_wallet_transactions_key");
            }
            // Longer than 100 characters: the column type rejects it before the CHECK (SQLSTATE 22001).
            assertThatThrownBy(() -> execute(connection, entrySql(wallet, "STARTING_BALANCE", 5, 1005, "x".repeat(101))))
                    .isInstanceOfSatisfying(PSQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("22001"));
            assertViolation(connection, entrySql(UUID.randomUUID(), "STARTING_BALANCE", 5, 5, "no-wallet"),
                    "fk_wallet_transactions_wallet");
            // The same key on another wallet is allowed, and the longest allowed key fits.
            UUID other = insertWallet(connection, insertUser(connection, "erin@example.com"), 0);
            insertEntry(connection, other, "STARTING_BALANCE", 1000, 1000, "starting-balance");
            insertEntry(connection, other, "STARTING_BALANCE", 1, 1001, "a" + "b:._-".repeat(19) + "1234");
            assertThat(queryString(connection, "SELECT count(*) FROM wallet_transactions")).isEqualTo("3");
        }
    }

    @Test
    void theLedgerIsAppendOnlyButRollbacksLeaveNothing() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            UUID user = insertUser(connection, "frank@example.com");
            UUID wallet = insertWallet(connection, user, 1000);
            long id = insertEntry(connection, wallet, "STARTING_BALANCE", 1000, 1000, "starting-balance");

            assertTriggerRejects(connection, "UPDATE wallet_transactions SET amount = 5", "wallet_transactions is append-only: UPDATE rejected");
            assertTriggerRejects(connection, "DELETE FROM wallet_transactions", "wallet_transactions is append-only: DELETE rejected");
            // A wallet with entries, and an account with a wallet, cannot be deleted.
            assertViolation(connection, "DELETE FROM wallets", "fk_wallet_transactions_wallet");
            assertViolation(connection, "DELETE FROM users WHERE id = '" + user + "'", "fk_wallets_user");

            connection.setAutoCommit(false);
            long rolledBack = insertEntry(connection, wallet, "STARTING_BALANCE", 5, 1005, "rolled-back");
            connection.rollback();
            connection.setAutoCommit(true);

            assertThat(queryString(connection, "SELECT count(*) FROM wallet_transactions")).isEqualTo("1");
            // Ids keep increasing and can have gaps.
            long next = insertEntry(connection, wallet, "STARTING_BALANCE", 5, 1005, "after-rollback");
            assertThat(rolledBack).isGreaterThan(id);
            assertThat(next).isGreaterThan(rolledBack);
        }
    }

    @Test
    void v6AddsEmptyTablesToADatabaseWithExistingAccounts() throws SQLException {
        // A schema at V5 with accounts, as Neon staging is today.
        String schema = migrated("5");
        try (Connection connection = connect(schema)) {
            insertUser(connection, "grace@example.com");
            insertUser(connection, "heidi@example.com");
            String before = queryString(connection, "SELECT string_agg(id || email || version || updated_at, ',' ORDER BY email) FROM users");

            flyway(schema, null).migrate();

            assertThat(queryString(connection, "SELECT string_agg(id || email || version || updated_at, ',' ORDER BY email) FROM users"))
                    .isEqualTo(before);
            assertThat(queryString(connection, "SELECT count(*) FROM wallets")).isEqualTo("0");
            assertThat(queryString(connection, "SELECT count(*) FROM wallet_transactions")).isEqualTo("0");
        }
    }

    @Test
    void theWalletOpeningInsertCreatesOneEmptyWalletAndIgnoresRepeats() throws SQLException {
        // The one native statement of WalletService.openWallet (W3-05), unchanged except for its parameter.
        String insert = WalletRepository.INSERT_IF_ABSENT.replace(":userId", "?");
        try (Connection connection = connect(migrated(null))) {
            UUID user = insertUser(connection, "ivan@example.com");

            assertThat(update(connection, insert, user)).isOne();
            assertThat(update(connection, insert, user)).isZero();

            assertThat(queryString(connection, "SELECT count(*) || ':' || min(balance) FROM wallets WHERE user_id = '" + user + "'"))
                    .isEqualTo("1:0");
            // Still only for existing accounts: ON CONFLICT does not cover the foreign key.
            assertThatThrownBy(() -> update(connection, insert, UUID.randomUUID()))
                    .isInstanceOfSatisfying(PSQLException.class,
                            error -> assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("fk_wallets_user"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int update(Connection connection, String sql, UUID parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            return statement.executeUpdate();
        }
    }

    /** A new schema migrated to {@code target} (all migrations when {@code null}). */
    private String migrated(String target) {
        String schema = "compat_" + SCHEMAS.incrementAndGet();
        flyway(schema, target).migrate();
        return schema;
    }

    private Flyway flyway(String schema, String target) {
        PostgreSQLContainer container = container();
        var configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private PostgreSQLContainer container() {
        return CONTAINERS.computeIfAbsent(image, name -> {
            PostgreSQLContainer container = new PostgreSQLContainer(name);
            container.start();
            return container;
        });
    }

    private Connection connect(String schema) throws SQLException {
        PostgreSQLContainer container = container();
        Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        execute(connection, "SET search_path TO " + schema);
        return connection;
    }

    private static UUID insertUser(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at)
                VALUES (?, ?, 'Player', now()) RETURNING id""")) {
            statement.setString(1, email);
            statement.setString(2, HASH);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static UUID insertWallet(Connection connection, UUID user, long balance) throws SQLException {
        return UUID.fromString(queryString(connection,
                "INSERT INTO wallets (user_id, balance) VALUES ('" + user + "', " + balance + ") RETURNING id"));
    }

    private static long insertEntry(Connection connection, UUID wallet, String type, long amount, long balanceAfter, String key)
            throws SQLException {
        return Long.parseLong(queryString(connection, entrySql(wallet, type, amount, balanceAfter, key) + " RETURNING id"));
    }

    private static String entrySql(UUID wallet, String type, long amount, long balanceAfter, String key) {
        return "INSERT INTO wallet_transactions (wallet_id, type, amount, balance_after, idempotency_key) VALUES ('"
                + wallet + "', '" + type + "', " + amount + ", " + balanceAfter + ", '" + key + "')";
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * The statement fails with the named constraint; a {@code null} name means any NOT NULL
     * violation (SQLSTATE 23502).
     */
    private static void assertViolation(Connection connection, String sql, String constraint) {
        assertThatThrownBy(() -> execute(connection, sql)).as(sql)
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    if (constraint == null) {
                        assertThat(error.getSQLState()).isEqualTo("23502");
                    } else {
                        assertThat(error.getServerErrorMessage()).isNotNull();
                        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                    }
                });
    }

    /** The statement fails in a trigger with SQLSTATE 23000 and the trigger's own message. */
    private static void assertTriggerRejects(Connection connection, String sql, String message) {
        assertThatThrownBy(() -> execute(connection, sql)).as(sql)
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo(INTEGRITY_VIOLATION);
                    assertThat(error.getServerErrorMessage().getMessage()).isEqualTo(message);
                });
    }
}
