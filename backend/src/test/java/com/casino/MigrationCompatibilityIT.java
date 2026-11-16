package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.casino.wallet.repository.WalletRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Flyway migrations, the wallet schema (V6), the wallet backfill (V7) and the Week 4 ledger types (V8) on PostgreSQL 17
 * (Docker Compose, Testcontainers, CI) **and** 18 (Neon runs 18.6; Render applies migrations at
 * start-up, so a migration that only works on 17 would break the next staging start). No Spring context.
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
                    .isEqualTo("8");
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
            // The test-only types of Week 3 were never allowed by the production schema, and still are not
            // (they were removed in W4-03 together with their test callback).
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
    void rouletteStakesAreDebitsAndPayoutsAreCredits() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            UUID wallet = insertWallet(connection, insertUser(connection, "olivia@example.com"), 1330);
            insertEntry(connection, wallet, "STARTING_BALANCE", 1000, 1000, "starting-balance");
            insertEntry(connection, wallet, "ROULETTE_STAKE", -30, 970, "roulette-round:r1:stake");
            insertEntry(connection, wallet, "ROULETTE_PAYOUT", 360, 1330, "roulette-round:r1:payout");

            // V8: a stake is strictly negative, a payout strictly positive (STARTING_BALANCE stays positive).
            assertViolation(connection, entrySql(wallet, "ROULETTE_STAKE", 30, 1360, "positive-stake"), "ck_wallet_transactions_direction");
            assertViolation(connection, entrySql(wallet, "ROULETTE_PAYOUT", -30, 1300, "negative-payout"),
                    "ck_wallet_transactions_direction");
            // Zero amounts stay forbidden for every type.
            for (String type : new String[] {"STARTING_BALANCE", "ROULETTE_STAKE", "ROULETTE_PAYOUT"}) {
                assertViolation(connection, entrySql(wallet, type, 0, 1330, "zero-" + type.toLowerCase(java.util.Locale.ROOT)),
                        "ck_wallet_transactions_amount");
            }
            // Only the exact spellings are types; an unknown type is reported by the type rule.
            assertViolation(connection, entrySql(wallet, "roulette_stake", -5, 1325, "lower-case"), "ck_wallet_transactions_type");
            assertViolation(connection, entrySql(wallet, "ROULETTE_REFUND", 5, 1335, "unknown"), "ck_wallet_transactions_type");
            // The V6 bounds, the key format and the unique key apply to the new types unchanged.
            assertViolation(connection, entrySql(wallet, "ROULETTE_PAYOUT", 9007199254740992L, 1330, "too-big"),
                    "ck_wallet_transactions_amount");
            assertViolation(connection, entrySql(wallet, "ROULETTE_STAKE", -2000, -670, "overdraw"), "ck_wallet_transactions_balance");
            assertViolation(connection, entrySql(wallet, "ROULETTE_STAKE", -30, 1300, "Roulette-Round"), "ck_wallet_transactions_key");
            assertViolation(connection, entrySql(wallet, "ROULETTE_STAKE", -30, 1300, "roulette-round:r1:stake"),
                    "uq_wallet_transactions_idempotency");
            // And the ledger stays append-only for them.
            assertTriggerRejects(connection, "UPDATE wallet_transactions SET amount = -31 WHERE type = 'ROULETTE_STAKE'",
                    "wallet_transactions is append-only: UPDATE rejected");
            assertTriggerRejects(connection, "DELETE FROM wallet_transactions WHERE type = 'ROULETTE_PAYOUT'",
                    "wallet_transactions is append-only: DELETE rejected");
            assertThat(queryString(connection, "SELECT count(*) FROM wallet_transactions")).isEqualTo("3");
        }
    }

    @Test
    void v8ChangesOnlyTheTwoTypeRulesOfADatabaseAtV7() throws SQLException {
        // The current staging path: a database at V7 with players, their wallets and other data.
        String schema = migrated("6");
        try (Connection connection = connect(schema)) {
            UUID alice = insertUser(connection, "alice@example.com");
            insertUser(connection, "bob@example.com", "PLAYER", "DISABLED");
            insertUser(connection, "admin@example.com", "ADMIN", "ACTIVE");
            UUID carol = insertUser(connection, "carol@example.com");
            insertEntry(connection, insertWallet(connection, carol, 250), "STARTING_BALANCE", 250, 250, "starting-balance");
            flyway(schema, "7").migrate();
            execute(connection, "INSERT INTO user_interests (user_id, interest_code) VALUES ('" + alice + "', 'HISTORY')");
            Map<String, String> tablesBefore = untouchedTables(connection);
            String ledgerBefore = rows(connection, "wallets") + rows(connection, "wallet_transactions");
            Map<String, String> constraintsBefore = walletConstraints(connection);
            String triggersBefore = walletTriggers(connection);

            flyway(schema, null).migrate();

            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("8");
            // Every wallet, balance and ledger entry is unchanged, byte for byte (ids and times included).
            assertThat(rows(connection, "wallets") + rows(connection, "wallet_transactions")).isEqualTo(ledgerBefore);
            assertThat(untouchedTables(connection)).isEqualTo(tablesBefore);
            assertThat(verification(connection)).containsExactly("0,0,0,0", "3,1,3,3");
            // Only the type list and the direction rule changed; keys, foreign keys, bounds, key format and
            // the triggers (append-only ledger, fixed owner) are identical.
            Map<String, String> constraintsAfter = walletConstraints(connection);
            assertThat(constraintsAfter.keySet()).isEqualTo(constraintsBefore.keySet());
            List<String> changed = constraintsAfter.keySet().stream()
                    .filter(name -> !constraintsAfter.get(name).equals(constraintsBefore.get(name))).toList();
            assertThat(changed).containsExactly("wallet_transactions.ck_wallet_transactions_direction",
                    "wallet_transactions.ck_wallet_transactions_type");
            assertThat(constraintsAfter.get("wallet_transactions.ck_wallet_transactions_type"))
                    .contains("STARTING_BALANCE", "ROULETTE_STAKE", "ROULETTE_PAYOUT").doesNotContain("TEST_");
            assertThat(walletTriggers(connection)).isEqualTo(triggersBefore).contains("trg_wallet_transactions_append_only",
                    "trg_wallets_owner_fixed", "trg_wallets_updated_at");
            // The new types work on the upgraded data.
            UUID aliceWallet = UUID.fromString(queryString(connection, "SELECT id FROM wallets WHERE user_id = '" + alice + "'"));
            insertEntry(connection, aliceWallet, "ROULETTE_STAKE", -100, 900, "roulette-round:r1:stake");
            execute(connection, "UPDATE wallets SET balance = 900 WHERE id = '" + aliceWallet + "'");
            assertThat(verification(connection)).containsExactly("0,0,0,0", "3,1,3,3");
        }
    }

    @Test
    void aFailedV8KeepsTheV6TypeRulesAndCanBeRepeated() throws SQLException {
        String schema = migrated("7");
        try (Connection connection = connect(schema)) {
            UUID wallet = insertWallet(connection, insertUser(connection, "alice@example.com"), 1000);
            insertEntry(connection, wallet, "STARTING_BALANCE", 1000, 1000, "starting-balance");
            String ledger = rows(connection, "wallets") + rows(connection, "wallet_transactions");
            // Fault injection in this throw-away schema only: V8 drops ck_wallet_transactions_direction by
            // name, after dropping the type rule in the same statement. Under another name the drop fails.
            execute(connection, "ALTER TABLE wallet_transactions RENAME CONSTRAINT ck_wallet_transactions_direction TO injected_direction");
            Map<String, String> injected = walletConstraints(connection);

            assertThatThrownBy(() -> flyway(schema, null).migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("ck_wallet_transactions_direction");

            // Rolled back as a whole: the V6 type rule is still in place, V8 not recorded, no row changed.
            assertThat(walletConstraints(connection)).isEqualTo(injected);
            assertThat(queryString(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '8'")).isEqualTo("0");
            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("7");
            assertViolation(connection, entrySql(wallet, "ROULETTE_STAKE", -5, 995, "stake-before-v8"), "ck_wallet_transactions_type");
            assertThat(rows(connection, "wallets") + rows(connection, "wallet_transactions")).isEqualTo(ledger);

            // Once the cause is gone, the next start applies V8 normally.
            execute(connection, "ALTER TABLE wallet_transactions RENAME CONSTRAINT injected_direction TO ck_wallet_transactions_direction");
            flyway(schema, null).migrate();
            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("8");
            insertEntry(connection, wallet, "ROULETTE_STAKE", -5, 995, "stake-after-v8");
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
    void v6AddsEmptyTablesAndV7OpensTheWalletsOfAccountsFromV5() throws SQLException {
        // A schema at V5 with accounts, as Neon staging is today; Render would apply V6 and V7 in one start.
        String schema = migrated("5");
        try (Connection connection = connect(schema)) {
            UUID grace = insertUser(connection, "grace@example.com");
            UUID heidi = insertUser(connection, "heidi@example.com", "PLAYER", "DISABLED");
            UUID admin = insertUser(connection, "admin@example.com", "ADMIN", "ACTIVE");
            String users = rows(connection, "users");

            flyway(schema, "6").migrate();

            assertThat(queryString(connection, "SELECT count(*) || ':' || (SELECT count(*) FROM wallet_transactions) FROM wallets"))
                    .isEqualTo("0:0");

            flyway(schema, "7").migrate();

            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("7");
            assertThat(wallet(connection, grace)).isEqualTo(STARTING_WALLET);
            assertThat(wallet(connection, heidi)).isEqualTo(STARTING_WALLET);
            assertThat(wallet(connection, admin)).isEqualTo("no wallet");
            assertThat(rows(connection, "users")).isEqualTo(users);
            assertThat(verification(connection)).containsExactly("0,0,0,0", "2,1,2,2");
        }
    }

    @Test
    void theMigrationCheckRecognisesTheStagingStateBeforeAndAfterTheWalletRollout() throws SQLException {
        // infrastructure/neon/verify-migrations.sql (W3-10), statements in file order:
        // 0 location, 1 history, 2 state, 3 expected tables, 4 unexpected tables, 5 counts.
        String schema = migrated("5");
        try (Connection connection = connect(schema)) {
            insertUser(connection, "grace@example.com");
            insertUser(connection, "heidi@example.com", "PLAYER", "DISABLED");
            insertUser(connection, "admin@example.com", "ADMIN", "ACTIVE");

            List<String> before = readOnlyScript(connection, "verify-migrations.sql");

            assertThat(before.get(0)).endsWith(",null"); // no Neon branch id outside Neon
            // Every row succeeded; the test schema has one row more than staging: Flyway's schema creation.
            assertThat(before.get(1).split("\\|")).allSatisfy(row -> assertThat(row).endsWith(",t"))
                    .filteredOn(row -> row.contains(",SQL,")).hasSize(5);
            assertThat(before.get(2)).isEqualTo("5,0,V5: before the Week 3 rollout");
            assertThat(before.get(3)).doesNotContain(",f", "wallet").contains("user_interests,5,t");
            assertThat(before.get(4)).isEqualTo("0");
            // 8 interests, 2 players (1 disabled), 1 admin, no sessions or throttle rows, no wallet tables yet.
            assertThat(before.get(5)).isEqualTo("8,2,1,1,0,0,null,null");

            flyway(schema, "7").migrate();
            List<String> after = readOnlyScript(connection, "verify-migrations.sql");

            assertThat(after.get(1).split("\\|")).allSatisfy(row -> assertThat(row).endsWith(",t"))
                    .filteredOn(row -> row.contains(",SQL,")).hasSize(7);
            assertThat(after.get(2)).isEqualTo("7,0,V7: Week 3 wallet schema");
            assertThat(after.get(3)).doesNotContain(",f").contains("wallets,6,t", "wallet_transactions,6,t");
            assertThat(after.get(4)).isEqualTo("0");
            assertThat(after.get(5)).isEqualTo("8,2,1,1,0,0,2,2");

            // V8 (W4-03) adds no table; the check recognises it as the Week 4 ledger state.
            flyway(schema, null).migrate();
            List<String> v8 = readOnlyScript(connection, "verify-migrations.sql");

            assertThat(v8.get(1).split("\\|")).allSatisfy(row -> assertThat(row).endsWith(",t"))
                    .filteredOn(row -> row.contains(",SQL,")).hasSize(8);
            assertThat(v8.get(2)).isEqualTo("8,0,V8: Week 4 ledger types");
            assertThat(v8.get(3)).isEqualTo(after.get(3));
            assertThat(v8.get(4)).isEqualTo("0");
            assertThat(v8.get(5)).isEqualTo("8,2,1,1,0,0,2,2");
        }
    }

    @Test
    void v7GivesEveryExistingPlayerOneStartingWalletAndChangesNothingElse() throws SQLException {
        String schema = migrated("6");
        try (Connection connection = connect(schema)) {
            UUID alice = insertUser(connection, "alice@example.com");
            UUID bob = insertUser(connection, "bob@example.com", "PLAYER", "DISABLED");
            UUID admin = insertUser(connection, "admin@example.com", "ADMIN", "ACTIVE");
            // Many players at once (AC-9.1).
            execute(connection, "INSERT INTO users (email, password_hash, display_name, adult_confirmed_at) "
                    + "SELECT 'player' || i || '@example.com', '" + HASH + "', 'Player', now() FROM generate_series(1, 25) i");
            // Registered between V6 and V7 (a developer database): the application opened the wallet, here
            // with another configured starting balance. It must stay exactly as it is (AC-9.3).
            UUID carol = insertUser(connection, "carol@example.com");
            UUID carolWallet = insertWallet(connection, carol, 250);
            insertEntry(connection, carolWallet, "STARTING_BALANCE", 250, 250, "starting-balance");
            // Authentication data of the existing accounts.
            execute(connection, "INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, "
                    + "max_inactive_interval, expiry_time, principal_name) VALUES ('00000000-0000-0000-0000-000000000001', "
                    + "'00000000-0000-0000-0000-000000000002', 1, 1, 1800, 2, '" + alice + "')");
            execute(connection, "INSERT INTO auth_throttle (throttle_key, attempt_count, window_started_at) "
                    + "VALUES ('LOGIN_IP:" + "a".repeat(64) + "', 3, now())");
            execute(connection, "INSERT INTO user_interests (user_id, interest_code) VALUES ('" + alice + "', 'HISTORY')");
            Map<String, String> before = untouchedTables(connection);
            String carolBefore = carolsWallet(connection, carol);

            flyway(schema, null).migrate();

            assertThat(wallet(connection, alice)).isEqualTo(STARTING_WALLET);
            assertThat(wallet(connection, bob)).isEqualTo(STARTING_WALLET);
            assertThat(wallet(connection, admin)).isEqualTo("no wallet");
            assertThat(carolsWallet(connection, carol)).isEqualTo(carolBefore);
            assertThat(untouchedTables(connection)).isEqualTo(before);
            // 2 + 25 new wallets with 1,000 coins each, carol's with 250.
            assertThat(queryString(connection, "SELECT count(*) || ':' || sum(balance) FROM wallets")).isEqualTo("28:27250");
            assertThat(verification(connection)).containsExactly("0,0,0,0", "28,1,28,28");
        }
    }

    @Test
    void theBackfillRunAgainChangesNothing() throws SQLException, IOException {
        String schema = migrated("6");
        try (Connection connection = connect(schema)) {
            insertUser(connection, "alice@example.com");
            insertUser(connection, "bob@example.com");
            insertUser(connection, "admin@example.com", "ADMIN", "ACTIVE");
            flyway(schema, null).migrate();
            String afterFirstRun = rows(connection, "wallets") + rows(connection, "wallet_transactions");

            // AC-9.2: the statement itself is idempotent (and Flyway would never run V7 twice anyway).
            execute(connection, backfillSql());
            execute(connection, backfillSql());
            assertThat(flyway(schema, null).migrate().migrationsExecuted).isZero();

            assertThat(rows(connection, "wallets") + rows(connection, "wallet_transactions")).isEqualTo(afterFirstRun);
            assertThat(verification(connection)).containsExactly("0,0,0,0", "2,1,2,2");
            // Flyway applies V7 once per database: an account created afterwards gets no wallet from it.
            // In the application, WalletOpener opens that wallet at registration (W3-05), never V7.
            UUID dave = insertUser(connection, "dave@example.com");
            assertThat(flyway(schema, null).migrate().migrationsExecuted).isZero();
            assertThat(wallet(connection, dave)).isEqualTo("no wallet");
            // Executed by hand (nothing in the application does this), the statement still serves only
            // accounts without a wallet and leaves the existing ones alone.
            execute(connection, backfillSql());
            assertThat(wallet(connection, dave)).isEqualTo(STARTING_WALLET);
            String others = "(SELECT * FROM wallets WHERE user_id <> '" + dave + "')";
            assertThat(rows(connection, others) + rows(connection, "(SELECT t.* FROM wallet_transactions t JOIN " + others
                    + " w ON w.id = t.wallet_id)")).isEqualTo(afterFirstRun);
            assertThat(verification(connection)).containsExactly("0,0,0,0", "3,1,3,3");
        }
    }

    @Test
    void aFailedBackfillLeavesNoWalletAndCanBeRepeated() throws SQLException {
        String schema = migrated("6");
        try (Connection connection = connect(schema)) {
            UUID alice = insertUser(connection, "alice@example.com");
            insertUser(connection, "bob@example.com");
            String before = rows(connection, "users") + rows(connection, "wallets") + rows(connection, "wallet_transactions");
            // Fault injection in this throw-away schema only: every ledger insert fails, i.e. after V7 has
            // already inserted the wallets in the same statement. No V6 rule is changed or disabled.
            execute(connection, """
                    CREATE FUNCTION injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'injected failure'; END $$""");
            execute(connection, "CREATE TRIGGER trg_injected_failure BEFORE INSERT ON wallet_transactions "
                    + "FOR EACH ROW EXECUTE FUNCTION injected_failure()");

            assertThatThrownBy(() -> flyway(schema, null).migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("injected failure");

            // Rolled back as a whole: no wallet without its entry, V7 not recorded, users unchanged.
            assertThat(rows(connection, "users") + rows(connection, "wallets") + rows(connection, "wallet_transactions")).isEqualTo(before);
            assertThat(queryString(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '7'")).isEqualTo("0");
            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("6");

            // Once the cause is gone, the next start applies V7 normally.
            execute(connection, "DROP TRIGGER trg_injected_failure ON wallet_transactions");
            execute(connection, "DROP FUNCTION injected_failure()");
            flyway(schema, null).migrate();
            assertThat(wallet(connection, alice)).isEqualTo(STARTING_WALLET);
            assertThat(verification(connection)).containsExactly("0,0,0,0", "2,0,2,2");
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

    /** {@link #wallet} of a player whose wallet was opened by V7 or by registration with 1,000 coins. */
    private static final String STARTING_WALLET = "1000 coins: STARTING_BALANCE 1000 -> 1000, key starting-balance, reference null";

    /** The V7 statement, exactly as Flyway runs it. */
    private static String backfillSql() throws IOException {
        try (InputStream sql = MigrationCompatibilityIT.class.getResourceAsStream("/db/migration/V7__backfill_existing_player_wallets.sql")) {
            return new String(sql.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The results of the read-only rollout check {@code infrastructure/neon/verify-wallets.sql}. */
    private static List<String> verification(Connection connection) throws SQLException {
        return readOnlyScript(connection, "verify-wallets.sql");
    }

    /**
     * Runs a read-only check of {@code infrastructure/neon} (psql meta-commands and comments removed)
     * and returns one result per statement: its rows separated by {@code |}, each row's values by
     * {@code ,} ({@code null} for SQL NULL).
     */
    private static List<String> readOnlyScript(Connection connection, String file) throws SQLException {
        String script;
        try {
            script = Files.readString(Path.of("..", "infrastructure", "neon", file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        String sql = script.lines().filter(line -> !line.startsWith("\\") && !line.strip().startsWith("--"))
                .reduce("", (all, line) -> all + line + "\n");
        List<String> results = new ArrayList<>();
        // As readonly-query.sh does: any write in the script would fail.
        execute(connection, "SET default_transaction_read_only = on");
        try {
            for (String statement : sql.split(";")) {
                if (statement.isBlank()) {
                    continue;
                }
                try (Statement query = connection.createStatement(); ResultSet row = query.executeQuery(statement)) {
                    List<String> rows = new ArrayList<>();
                    while (row.next()) {
                        List<String> values = new ArrayList<>();
                        for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                            values.add(row.getString(column));
                        }
                        rows.add(String.join(",", values));
                    }
                    results.add(String.join("|", rows));
                }
            }
        } finally {
            execute(connection, "SET default_transaction_read_only = off");
        }
        return results;
    }

    /** Balance and ledger entries of a user's wallet, or {@code "no wallet"}. */
    private static String wallet(Connection connection, UUID user) throws SQLException {
        return queryString(connection, """
                SELECT coalesce((SELECT w.balance || ' coins: ' || string_agg(t.type || ' ' || t.amount || ' -> ' || t.balance_after
                                        || ', key ' || t.idempotency_key || ', reference ' || coalesce(t.reference, 'null'), '; ' ORDER BY t.id)
                                 FROM wallets w LEFT JOIN wallet_transactions t ON t.wallet_id = w.id
                                 WHERE w.user_id = '%s' GROUP BY w.id, w.balance), 'no wallet')""".formatted(user));
    }

    /** Every column of a user's wallet and its entries, including ids and times. */
    private static String carolsWallet(Connection connection, UUID user) throws SQLException {
        return queryString(connection, """
                SELECT w::text || ' / ' || (SELECT string_agg(t::text, '|' ORDER BY t.id) FROM wallet_transactions t WHERE t.wallet_id = w.id)
                FROM wallets w WHERE w.user_id = '%s'""".formatted(user));
    }

    /** Every constraint of {@code wallets} and {@code wallet_transactions}: {@code table.name} → definition. */
    private static Map<String, String> walletConstraints(Connection connection) throws SQLException {
        Map<String, String> constraints = new java.util.TreeMap<>();
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("""
                SELECT c.conrelid::regclass::text || '.' || c.conname, pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                WHERE c.conrelid IN ('wallets'::regclass, 'wallet_transactions'::regclass)""")) {
            while (row.next()) {
                constraints.put(row.getString(1), row.getString(2));
            }
        }
        return constraints;
    }

    /** The user-defined triggers of {@code wallets} and {@code wallet_transactions} with their definitions. */
    private static String walletTriggers(Connection connection) throws SQLException {
        return queryString(connection, """
                SELECT string_agg(pg_get_triggerdef(t.oid), '|' ORDER BY t.tgname)
                FROM pg_trigger t
                WHERE NOT t.tgisinternal AND t.tgrelid IN ('wallets'::regclass, 'wallet_transactions'::regclass)""");
    }

    /** All rows of a table as text, in a stable order. */
    private static String rows(Connection connection, String table) throws SQLException {
        return queryString(connection, "SELECT coalesce(string_agg(t::text, '|' ORDER BY t::text), '') FROM " + table + " t");
    }

    /** Accounts, authentication and profile data, which V7 must not change. */
    private static Map<String, String> untouchedTables(Connection connection) throws SQLException {
        Map<String, String> tables = new LinkedHashMap<>();
        for (String table : List.of("users", "spring_session", "spring_session_attributes", "auth_throttle", "user_interests", "interests")) {
            tables.put(table, rows(connection, table));
        }
        return tables;
    }

    private static UUID insertUser(Connection connection, String email, String role, String status) throws SQLException {
        UUID user = insertUser(connection, email);
        execute(connection, "UPDATE users SET role = '" + role + "', status = '" + status + "' WHERE id = '" + user + "'");
        return user;
    }

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
