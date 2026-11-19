package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * The Flyway migrations, the wallet schema (V6), the wallet backfill (V7), the Week 4 ledger types (V8) and the roulette
 * rounds (V9) on PostgreSQL 17 (Docker Compose, Testcontainers, CI) **and** 18 (Neon runs 18.6; Render applies migrations at
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
                    .isEqualTo("9");
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

            flyway(schema, "8").migrate();

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
            flyway(schema, "8").migrate();
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
        // 0 location, 1 history, 2 state, 3 expected tables, 4 unexpected tables, 5 counts (roulette tables from V9).
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
            assertThat(before.get(5)).isEqualTo("8,2,1,1,0,0,null,null,null,null");

            flyway(schema, "7").migrate();
            List<String> after = readOnlyScript(connection, "verify-migrations.sql");

            assertThat(after.get(1).split("\\|")).allSatisfy(row -> assertThat(row).endsWith(",t"))
                    .filteredOn(row -> row.contains(",SQL,")).hasSize(7);
            assertThat(after.get(2)).isEqualTo("7,0,V7: Week 3 wallet schema");
            assertThat(after.get(3)).doesNotContain(",f").contains("wallets,6,t", "wallet_transactions,6,t");
            assertThat(after.get(4)).isEqualTo("0");
            assertThat(after.get(5)).isEqualTo("8,2,1,1,0,0,2,2,null,null");

            // V8 (W4-03) adds no table; the check recognises it as the Week 4 ledger state.
            flyway(schema, "8").migrate();
            List<String> v8 = readOnlyScript(connection, "verify-migrations.sql");

            assertThat(v8.get(1).split("\\|")).allSatisfy(row -> assertThat(row).endsWith(",t"))
                    .filteredOn(row -> row.contains(",SQL,")).hasSize(8);
            assertThat(v8.get(2)).isEqualTo("8,0,V8: Week 4 ledger types");
            assertThat(v8.get(3)).isEqualTo(after.get(3));
            assertThat(v8.get(4)).isEqualTo("0");
            assertThat(v8.get(5)).isEqualTo("8,2,1,1,0,0,2,2,null,null");

            // V9 (W4-05) adds the two roulette tables; they are expected, not unexpected.
            flyway(schema, null).migrate();
            List<String> v9 = readOnlyScript(connection, "verify-migrations.sql");

            assertThat(v9.get(1).split("\\|")).allSatisfy(row -> assertThat(row).endsWith(",t"))
                    .filteredOn(row -> row.contains(",SQL,")).hasSize(9);
            assertThat(v9.get(2)).isEqualTo("9,0,V9: Week 4 roulette rounds");
            assertThat(v9.get(3)).doesNotContain(",f").contains("roulette_rounds,9,t", "roulette_bets,9,t");
            assertThat(v9.get(4)).isEqualTo("0");
            assertThat(v9.get(5)).isEqualTo("8,2,1,1,0,0,2,2,0,0");
        }
    }

    // ------------------------------------------------------------------ V9: roulette rounds (W4-05)

    @Test
    void v9AddsTheEmptyRouletteTablesAndChangesNothingElse() throws SQLException {
        // A database at V8 with players, wallets and ledger entries of every type (the state after a
        // future V8 rollout); V9 must not touch any of it.
        String schema = migrated("6");
        try (Connection connection = connect(schema)) {
            insertUser(connection, "alice@example.com");
            insertUser(connection, "bob@example.com", "PLAYER", "DISABLED");
            insertUser(connection, "admin@example.com", "ADMIN", "ACTIVE");
            flyway(schema, "8").migrate();
            UUID wallet = UUID.fromString(queryString(connection, "SELECT id FROM wallets ORDER BY id LIMIT 1"));
            insertEntry(connection, wallet, "ROULETTE_STAKE", -30, 970, "roulette-round:r1:stake");
            insertEntry(connection, wallet, "ROULETTE_PAYOUT", 360, 1330, "roulette-round:r1:payout");
            execute(connection, "UPDATE wallets SET balance = 1330 WHERE id = '" + wallet + "'");
            Map<String, String> tablesBefore = untouchedTables(connection);
            String ledgerBefore = rows(connection, "wallets") + rows(connection, "wallet_transactions");
            Map<String, String> constraintsBefore = walletConstraints(connection);
            String triggersBefore = walletTriggers(connection);

            flyway(schema, null).migrate();

            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("9");
            assertThat(rows(connection, "wallets") + rows(connection, "wallet_transactions")).isEqualTo(ledgerBefore);
            assertThat(untouchedTables(connection)).isEqualTo(tablesBefore);
            assertThat(walletConstraints(connection)).isEqualTo(constraintsBefore);
            assertThat(walletTriggers(connection)).isEqualTo(triggersBefore);
            assertThat(verification(connection)).containsExactly("0,0,0,0", "2,1,2,2");
            assertThat(queryString(connection, "SELECT (SELECT count(*) FROM roulette_rounds) || ':' || (SELECT count(*) FROM roulette_bets)"))
                    .isEqualTo("0:0");
            // The new tables carry exactly the planned constraints and both append-only triggers.
            assertThat(constraintNames(connection, "roulette_rounds")).containsExactly("ck_roulette_rounds_balance",
                    "ck_roulette_rounds_distinct_entries", "ck_roulette_rounds_number", "ck_roulette_rounds_payout",
                    "ck_roulette_rounds_payout_entry", "ck_roulette_rounds_stake", "fk_roulette_rounds_payout_entry",
                    "fk_roulette_rounds_stake_entry", "fk_roulette_rounds_user", "roulette_rounds_pkey",
                    "uq_roulette_rounds_payout_entry", "uq_roulette_rounds_request", "uq_roulette_rounds_stake_entry");
            assertThat(constraintNames(connection, "roulette_bets")).containsExactly("ck_roulette_bets_amount",
                    "ck_roulette_bets_category", "ck_roulette_bets_option", "ck_roulette_bets_payout", "fk_roulette_bets_round",
                    "roulette_bets_pkey", "uq_roulette_bets_selection");
            assertThat(queryString(connection, """
                    SELECT string_agg(tgrelid::regclass::text || '.' || tgname, ',' ORDER BY tgname COLLATE "C") FROM pg_trigger
                    WHERE NOT tgisinternal AND tgrelid IN ('roulette_rounds'::regclass, 'roulette_bets'::regclass)"""))
                    .isEqualTo("roulette_bets.trg_roulette_bets_append_only,roulette_rounds.trg_roulette_rounds_append_only");
        }
    }

    @Test
    void roundsFollowTheirRules() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            Ledger ledger = ledger(connection, "rita@example.com");
            UUID request = UUID.randomUUID();
            execute(connection, roundSql(UUID.randomUUID(), ledger.user(), request, 17, 30, 360, 1330, ledger.stake(), ledger.payout()));
            // A losing round has no payout entry.
            long otherStake = insertEntry(connection, ledger.wallet(), "ROULETTE_STAKE", -50, 1280, "roulette-round:r2:stake");
            execute(connection, roundSql(UUID.randomUUID(), ledger.user(), UUID.randomUUID(), 8, 50, 0, 1280, otherStake, null));

            long freshStake = insertEntry(connection, ledger.wallet(), "ROULETTE_STAKE", -10, 1270, "roulette-round:r3:stake");
            long freshPayout = insertEntry(connection, ledger.wallet(), "ROULETTE_PAYOUT", 20, 1290, "roulette-round:r3:payout");
            UUID user = ledger.user();
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 37, 10, 20, 1290, freshStake, freshPayout),
                    "ck_roulette_rounds_number");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), -1, 10, 20, 1290, freshStake, freshPayout),
                    "ck_roulette_rounds_number");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 0, 20, 1290, freshStake, freshPayout),
                    "ck_roulette_rounds_stake");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, -1, 1290, freshStake, freshPayout),
                    "ck_roulette_rounds_payout");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, -1, freshStake, freshPayout),
                    "ck_roulette_rounds_balance");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, 9007199254740992L, freshStake,
                    freshPayout), "ck_roulette_rounds_balance");
            // A payout entry exactly when something was won.
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 0, 1290, freshStake, freshPayout),
                    "ck_roulette_rounds_payout_entry");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, 1290, freshStake, null),
                    "ck_roulette_rounds_payout_entry");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, 1290, freshStake, freshStake),
                    "ck_roulette_rounds_distinct_entries");
            // One round per request and player; an entry belongs to one round only.
            assertViolation(connection, roundSql(UUID.randomUUID(), user, request, 5, 10, 20, 1290, freshStake, freshPayout),
                    "uq_roulette_rounds_request");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, 1290, ledger.stake(), freshPayout),
                    "uq_roulette_rounds_stake_entry");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, 1290, freshStake, ledger.payout()),
                    "uq_roulette_rounds_payout_entry");
            // Every reference must exist.
            assertViolation(connection, roundSql(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, 10, 20, 1290, freshStake,
                    freshPayout), "fk_roulette_rounds_user");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, 1290, 999_999, freshPayout),
                    "fk_roulette_rounds_stake_entry");
            assertViolation(connection, roundSql(UUID.randomUUID(), user, UUID.randomUUID(), 5, 10, 20, 1290, freshStake, 999_999L),
                    "fk_roulette_rounds_payout_entry");
            assertViolation(connection, "INSERT INTO roulette_rounds (id, user_id, winning_number, total_stake, total_payout, "
                    + "balance_after, stake_transaction_id) VALUES ('" + UUID.randomUUID() + "', '" + user + "', 5, 10, 0, 1290, "
                    + freshStake + ")", null);
            // The same request id of another player is a different request.
            UUID other = insertUser(connection, "sam@example.com");
            execute(connection, roundSql(UUID.randomUUID(), other, request, 5, 10, 20, 1290, freshStake, freshPayout));
            assertThat(queryString(connection, "SELECT count(*) FROM roulette_rounds")).isEqualTo("3");
        }
    }

    @Test
    void betsAllowExactlyTheOptionOfTheirCategory() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            Ledger ledger = ledger(connection, "bea@example.com");
            UUID round = UUID.randomUUID();
            execute(connection, roundSql(round, ledger.user(), UUID.randomUUID(), 17, 30, 360, 1330, ledger.stake(), ledger.payout()));
            // One valid bet of every category and option (payouts for 17: straight 17, black, odd, low, second dozen win).
            execute(connection, betSql(round, "STRAIGHT", null, 17, 10, 360));
            execute(connection, betSql(round, "STRAIGHT", null, 0, 10, 0));
            execute(connection, betSql(round, "STRAIGHT", null, 36, 10, 0));
            for (String[] option : new String[][] {{"RED_BLACK", "RED", "0"}, {"RED_BLACK", "BLACK", "20"}, {"EVEN_ODD", "EVEN", "0"},
                    {"EVEN_ODD", "ODD", "20"}, {"LOW_HIGH", "LOW", "20"}, {"LOW_HIGH", "HIGH", "0"}, {"DOZEN", "FIRST", "0"},
                    {"DOZEN", "SECOND", "30"}, {"DOZEN", "THIRD", "0"}}) {
                execute(connection, betSql(round, option[0], option[1], null, 10, Long.parseLong(option[2])));
            }
            assertThat(queryString(connection, "SELECT count(*) FROM roulette_bets")).isEqualTo("12");

            // The number only for STRAIGHT, within 0-36; a selection only for the other categories, with their own options.
            assertViolation(connection, betSql(round, "STRAIGHT", null, 37, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "STRAIGHT", null, -1, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "STRAIGHT", null, null, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "STRAIGHT", "RED", 5, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "RED_BLACK", "RED", 5, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "RED_BLACK", null, null, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "RED_BLACK", "GREEN", null, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "EVEN_ODD", "RED", null, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "LOW_HIGH", "EVEN", null, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "DOZEN", "FOURTH", null, 10, 0), "ck_roulette_bets_option");
            assertViolation(connection, betSql(round, "DOZEN", "first", null, 10, 0), "ck_roulette_bets_option");
            // No other category (no columns, splits or variants).
            assertViolation(connection, betSql(round, "COLUMN", "FIRST", null, 10, 0), "ck_roulette_bets_category");
            assertViolation(connection, betSql(round, "SPLIT", null, 17, 10, 0), "ck_roulette_bets_category");
            // Amounts and exact payout multiples (36x, 3x, 2x or nothing).
            assertViolation(connection, betSql(round, "STRAIGHT", null, 5, 0, 0), "ck_roulette_bets_amount");
            assertViolation(connection, betSql(round, "STRAIGHT", null, 5, 9007199254740992L, 0), "ck_roulette_bets_amount");
            assertViolation(connection, betSql(round, "STRAIGHT", null, 5, 10, 350), "ck_roulette_bets_payout");
            assertViolation(connection, betSql(round, "DOZEN", "FIRST", null, 10, 20), "ck_roulette_bets_payout");
            assertViolation(connection, betSql(round, "RED_BLACK", "RED", null, 10, 30), "ck_roulette_bets_payout");
            assertViolation(connection, betSql(round, "EVEN_ODD", "ODD", null, 10, -20), "ck_roulette_bets_payout");
            // A selection at most once per round; a different number is a different selection.
            assertViolation(connection, betSql(round, "STRAIGHT", null, 17, 5, 0), "uq_roulette_bets_selection");
            assertViolation(connection, betSql(round, "RED_BLACK", "RED", null, 5, 0), "uq_roulette_bets_selection");
            execute(connection, betSql(round, "STRAIGHT", null, 18, 5, 0));
            assertViolation(connection, betSql(UUID.randomUUID(), "STRAIGHT", null, 1, 5, 0), "fk_roulette_bets_round");
            assertThat(queryString(connection, "SELECT count(*) FROM roulette_bets")).isEqualTo("13");
        }
    }

    @Test
    void roundsAndBetsAreAppendOnlyAndKeepTheirReferences() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            Ledger ledger = ledger(connection, "ana@example.com");
            UUID round = UUID.randomUUID();
            execute(connection, roundSql(round, ledger.user(), UUID.randomUUID(), 17, 10, 360, 1330, ledger.stake(), ledger.payout()));
            execute(connection, betSql(round, "STRAIGHT", null, 17, 10, 360));

            assertTriggerRejects(connection, "UPDATE roulette_rounds SET winning_number = 5", "roulette_rounds is append-only: UPDATE rejected");
            assertTriggerRejects(connection, "UPDATE roulette_rounds SET total_payout = 0, payout_transaction_id = NULL",
                    "roulette_rounds is append-only: UPDATE rejected");
            assertTriggerRejects(connection, "DELETE FROM roulette_rounds", "roulette_rounds is append-only: DELETE rejected");
            assertTriggerRejects(connection, "UPDATE roulette_bets SET payout = 0", "roulette_bets is append-only: UPDATE rejected");
            assertTriggerRejects(connection, "DELETE FROM roulette_bets", "roulette_bets is append-only: DELETE rejected");
            // The referenced ledger entries stay (their own trigger and the foreign keys).
            assertTriggerRejects(connection, "DELETE FROM wallet_transactions WHERE id = " + ledger.stake(),
                    "wallet_transactions is append-only: DELETE rejected");
            assertThat(queryString(connection, "SELECT count(*) || ':' || (SELECT count(*) FROM roulette_bets) FROM roulette_rounds"))
                    .isEqualTo("1:1");

            // A rolled-back round leaves nothing (it is never half stored).
            connection.setAutoCommit(false);
            long stake = insertEntry(connection, ledger.wallet(), "ROULETTE_STAKE", -10, 1320, "roulette-round:rolled-back:stake");
            UUID rolledBack = UUID.randomUUID();
            execute(connection, roundSql(rolledBack, ledger.user(), UUID.randomUUID(), 3, 10, 0, 1320, stake, null));
            execute(connection, betSql(rolledBack, "DOZEN", "FIRST", null, 10, 30));
            connection.rollback();
            connection.setAutoCommit(true);
            assertThat(queryString(connection, "SELECT count(*) || ':' || (SELECT count(*) FROM roulette_bets) FROM roulette_rounds"))
                    .isEqualTo("1:1");
        }
    }

    @Test
    void theForeignKeysProveExistenceNotOwnership() throws SQLException {
        // Documented gap (docs/plans/week-04.md §5.7): the database proves that a round's entries exist and
        // keeps them, but not that they belong to the round's player. RouletteService (W4-06) writes both
        // from one operation; the W4-08 reconciliation detects any mismatch. This test pins the behaviour.
        try (Connection connection = connect(migrated(null))) {
            Ledger ledger = ledger(connection, "owner@example.com");
            UUID stranger = insertUser(connection, "stranger@example.com"); // no wallet at all

            execute(connection, roundSql(UUID.randomUUID(), stranger, UUID.randomUUID(), 17, 30, 360, 1330, ledger.stake(),
                    ledger.payout()));

            // ON DELETE RESTRICT: the account of a round cannot be deleted (no wallet blocks it here).
            assertViolation(connection, "DELETE FROM users WHERE id = '" + stranger + "'", "fk_roulette_rounds_user");
        }
    }

    // ------------------------------------------------------------------ W4-08: read-only roulette reconciliation

    /** The violation counters of {@code verify-roulette.sql} (statement 1), in column order. */
    private static final List<String> ROULETTE_COUNTERS = List.of("rounds_without_1_to_10_bets",
            "rounds_with_totals_not_matching_bets", "bets_with_wrong_payout", "rounds_with_missing_entries",
            "entries_of_another_player", "entries_with_wrong_type", "entries_with_wrong_amount", "entries_with_wrong_reference",
            "payouts_not_after_their_stake", "rounds_with_wrong_balance_after", "roulette_entries_not_in_exactly_one_round");

    @Test
    void theRouletteCheckReportsNothingForAFreshV9DatabaseAndForConsistentRounds() throws SQLException {
        try (Connection connection = connect(migrated(null))) {
            // Fresh V9: no player, no round.
            assertThat(readOnlyScript(connection, "verify-roulette.sql")).containsExactly(zeros(), "0,0,0,0,0,0");
            Book book = new Book(connection);
            UUID alice = book.player("alice@example.com");
            UUID bob = book.player("bob@example.com");
            // Players with wallets, still no round.
            assertThat(readOnlyScript(connection, "verify-roulette.sql")).containsExactly(zeros(), "0,0,0,0,0,0");

            // 17 (black, odd, low, second dozen): a straight win next to a losing red bet.
            book.round(alice, 17, bet("STRAIGHT", null, 17, 10, 360), bet("RED_BLACK", "RED", null, 20, 0));
            // Zero: only the straight bet on 0 wins; every other category loses.
            book.round(alice, 0, bet("STRAIGHT", null, 0, 5, 180), bet("EVEN_ODD", "EVEN", null, 10, 0),
                    bet("LOW_HIGH", "LOW", null, 10, 0), bet("DOZEN", "FIRST", null, 10, 0), bet("RED_BLACK", "BLACK", null, 10, 0));
            // A loss has no payout entry.
            book.round(bob, 8, bet("DOZEN", "THIRD", null, 50, 0));
            // 36 is red, even, high and in the third dozen: every category wins.
            book.round(bob, 36, bet("RED_BLACK", "RED", null, 1, 2), bet("EVEN_ODD", "EVEN", null, 1, 2),
                    bet("LOW_HIGH", "HIGH", null, 1, 2), bet("DOZEN", "THIRD", null, 1, 3), bet("STRAIGHT", null, 36, 1, 36));

            // rounds, bets, players with rounds, winning rounds, stake entries, payout entries.
            assertThat(readOnlyScript(connection, "verify-roulette.sql")).containsExactly(zeros(), "4,13,2,3,4,3");
            // The Week 3 wallet check still holds, and the migration check counts the new rows.
            assertThat(verification(connection)).containsExactly("0,0,0,0", "2,0,2,2");
            assertThat(readOnlyScript(connection, "verify-migrations.sql").get(5)).isEqualTo("8,2,0,0,0,0,2,9,4,13");
        }
    }

    @Test
    void theRouletteCheckCountsEveryKindOfInconsistency() throws SQLException {
        // Every step writes rows that V9 accepts (the database cannot see these mistakes, §5.7) but that
        // break one reconciliation rule. Rows are append-only, so the counters add up step by step.
        try (Connection connection = connect(migrated(null))) {
            Book book = new Book(connection);
            UUID alice = book.player("alice@example.com");
            UUID bob = book.player("bob@example.com");
            Entry firstStake = book.round(alice, 17, bet("STRAIGHT", null, 17, 10, 360));
            Map<String, Long> expected = new LinkedHashMap<>();
            ROULETTE_COUNTERS.forEach(name -> expected.put(name, 0L));
            assertThat(rouletteCounters(connection)).isEqualTo(expected);

            // Bob's round, paid from Alice's wallet.
            UUID round = UUID.randomUUID();
            Entry stake = book.entry(alice, "ROULETTE_STAKE", -10, key(round, "stake"), round.toString());
            book.insert(round, bob, 8, 10, 0, stake.balanceAfter(), stake.id(), null, bet("STRAIGHT", null, 1, 10, 0));
            expectMore(connection, expected, "entries_of_another_player");

            // The payout entry has another type (the amount, key and reference are right).
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -10, key(round, "stake"), round.toString());
            Entry payout = book.entry(bob, "STARTING_BALANCE", 20, key(round, "payout"), round.toString());
            book.insert(round, bob, 17, 10, 20, payout.balanceAfter(), stake.id(), payout.id(), bet("RED_BLACK", "BLACK", null, 10, 20));
            expectMore(connection, expected, "entries_with_wrong_type");

            // The stake entry debits 25 for a round of 30.
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -25, key(round, "stake"), round.toString());
            book.insert(round, bob, 8, 30, 0, stake.balanceAfter(), stake.id(), null, bet("LOW_HIGH", "HIGH", null, 30, 0));
            expectMore(connection, expected, "entries_with_wrong_amount");

            // No reference at all (NULL must count, not slip through three-valued logic) ...
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -10, key(round, "stake"), null);
            book.insert(round, bob, 8, 10, 0, stake.balanceAfter(), stake.id(), null, bet("STRAIGHT", null, 2, 10, 0));
            expectMore(connection, expected, "entries_with_wrong_reference");
            // ... and the key of another round.
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -10, key(UUID.randomUUID(), "stake"), round.toString());
            book.insert(round, bob, 8, 10, 0, stake.balanceAfter(), stake.id(), null, bet("STRAIGHT", null, 3, 10, 0));
            expectMore(connection, expected, "entries_with_wrong_reference");

            // The payout was written before the stake.
            round = UUID.randomUUID();
            payout = book.entry(alice, "ROULETTE_PAYOUT", 360, key(round, "payout"), round.toString());
            stake = book.entry(alice, "ROULETTE_STAKE", -10, key(round, "stake"), round.toString());
            book.insert(round, alice, 17, 10, 360, payout.balanceAfter(), stake.id(), payout.id(), bet("STRAIGHT", null, 17, 10, 360));
            expectMore(connection, expected, "payouts_not_after_their_stake");

            // The stored balance is not the one after the round's last entry.
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -10, key(round, "stake"), round.toString());
            book.insert(round, bob, 8, 10, 0, stake.balanceAfter() + 1, stake.id(), null, bet("STRAIGHT", null, 4, 10, 0));
            expectMore(connection, expected, "rounds_with_wrong_balance_after");

            // A total stake of 30 for bets of 10 and 10.
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -30, key(round, "stake"), round.toString());
            book.insert(round, bob, 8, 30, 0, stake.balanceAfter(), stake.id(), null, bet("STRAIGHT", null, 5, 10, 0),
                    bet("STRAIGHT", null, 6, 10, 0));
            expectMore(connection, expected, "rounds_with_totals_not_matching_bets");

            // Eleven bets (the totals add up).
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -11, key(round, "stake"), round.toString());
            List<Bet> eleven = new ArrayList<>();
            for (int number = 1; number <= 11; number++) {
                eleven.add(bet("STRAIGHT", null, number, 1, 0));
            }
            book.insert(round, bob, 0, 11, 0, stake.balanceAfter(), stake.id(), null, eleven.toArray(Bet[]::new));
            expectMore(connection, expected, "rounds_without_1_to_10_bets");

            // A straight bet on 17 paid on 5, and a dozen paid on zero (both exact multiples, so V9 accepts them).
            book.round(alice, 5, bet("STRAIGHT", null, 17, 10, 360));
            expectMore(connection, expected, "bets_with_wrong_payout");
            book.round(bob, 0, bet("DOZEN", "FIRST", null, 10, 30));
            expectMore(connection, expected, "bets_with_wrong_payout");

            // A stake without any round.
            book.entry(bob, "ROULETTE_STAKE", -10, "roulette-round:orphan:stake", null);
            expectMore(connection, expected, "roulette_entries_not_in_exactly_one_round");

            // A round without bets: no bets, and totals that the bets do not explain.
            round = UUID.randomUUID();
            stake = book.entry(bob, "ROULETTE_STAKE", -10, key(round, "stake"), round.toString());
            book.insert(round, bob, 8, 10, 0, stake.balanceAfter(), stake.id(), null);
            expectMore(connection, expected, "rounds_without_1_to_10_bets", "rounds_with_totals_not_matching_bets");

            // One entry serving two rounds: the first round's stake as the payout of another round.
            round = UUID.randomUUID();
            stake = book.entry(alice, "ROULETTE_STAKE", -10, key(round, "stake"), round.toString());
            book.insert(round, alice, 17, 10, 360, firstStake.balanceAfter(), stake.id(), firstStake.id(), bet("STRAIGHT", null, 17, 10, 360));
            expectMore(connection, expected, "entries_with_wrong_type", "entries_with_wrong_amount", "entries_with_wrong_reference",
                    "payouts_not_after_their_stake", "roulette_entries_not_in_exactly_one_round");

            // A missing entry cannot be written at all (foreign keys and ck_roulette_rounds_payout_entry), so
            // rounds_with_missing_entries stays 0; the other counters also count a missing entry (LEFT JOIN).
            assertThat(expected.get("rounds_with_missing_entries")).isZero();
        }
    }

    /** {@code verify-roulette.sql} statement 1 as counter name → value. */
    private static Map<String, Long> rouletteCounters(Connection connection) throws SQLException {
        String[] values = readOnlyScript(connection, "verify-roulette.sql").get(0).split(",");
        Map<String, Long> counters = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            counters.put(i < ROULETTE_COUNTERS.size() ? ROULETTE_COUNTERS.get(i) : "column " + (i + 1), Long.parseLong(values[i]));
        }
        return counters;
    }

    /** Adds one to each named counter and asserts that the script reports exactly the expected counts. */
    private static void expectMore(Connection connection, Map<String, Long> expected, String... counters) throws SQLException {
        for (String counter : counters) {
            assertThat(expected).containsKey(counter);
            expected.merge(counter, 1L, Long::sum);
        }
        assertThat(rouletteCounters(connection)).as("after a step breaking %s", List.of(counters)).isEqualTo(expected);
    }

    private static String zeros() {
        return String.join(",", java.util.Collections.nCopies(ROULETTE_COUNTERS.size(), "0"));
    }

    private static String key(UUID round, String entry) {
        return "roulette-round:" + round + ":" + entry;
    }

    private static Bet bet(String category, String selection, Integer number, long amount, long payout) {
        return new Bet(category, selection, number, amount, payout);
    }

    private record Bet(String category, String selection, Integer number, long amount, long payout) {
    }

    private record Entry(long id, long balanceAfter) {
    }

    /**
     * Players, ledger entries and rounds written with plain SQL, as {@code RouletteService} writes them:
     * each wallet's balance and {@code balance_after} follow its entries, so the Week 3 checks hold. Every
     * part can also be written wrongly on purpose ({@link #entry}, {@link #insert}).
     */
    private static final class Book {

        private final Connection connection;
        private final Map<UUID, UUID> walletOf = new HashMap<>();
        private final Map<UUID, Long> balanceOf = new HashMap<>();

        Book(Connection connection) {
            this.connection = connection;
        }

        /** A player with a wallet opened with 1,000 coins, as at registration. */
        UUID player(String email) throws SQLException {
            UUID user = insertUser(connection, email);
            walletOf.put(user, insertWallet(connection, user, 0));
            balanceOf.put(user, 0L);
            entry(user, "STARTING_BALANCE", 1000, "starting-balance", null);
            return user;
        }

        /** A ledger entry in the player's wallet; the wallet's balance follows it. */
        Entry entry(UUID user, String type, long amount, String key, String reference) throws SQLException {
            long balance = balanceOf.merge(user, amount, Long::sum);
            long id = Long.parseLong(queryString(connection, "INSERT INTO wallet_transactions (wallet_id, type, amount, balance_after, "
                    + "idempotency_key, reference) VALUES ('" + walletOf.get(user) + "', '" + type + "', " + amount + ", " + balance + ", '"
                    + key + "', " + (reference == null ? "NULL" : "'" + reference + "'") + ") RETURNING id"));
            execute(connection, "UPDATE wallets SET balance = " + balance + " WHERE id = '" + walletOf.get(user) + "'");
            return new Entry(id, balance);
        }

        /** A consistent round: stake entry, payout entry when something was won, round and bets. Returns the stake entry. */
        Entry round(UUID user, int winningNumber, Bet... bets) throws SQLException {
            UUID round = UUID.randomUUID();
            long totalStake = 0;
            long totalPayout = 0;
            for (Bet bet : bets) {
                totalStake += bet.amount();
                totalPayout += bet.payout();
            }
            Entry stake = entry(user, "ROULETTE_STAKE", -totalStake, key(round, "stake"), round.toString());
            Entry payout = totalPayout > 0 ? entry(user, "ROULETTE_PAYOUT", totalPayout, key(round, "payout"), round.toString()) : null;
            insert(round, user, winningNumber, totalStake, totalPayout, payout == null ? stake.balanceAfter() : payout.balanceAfter(),
                    stake.id(), payout == null ? null : payout.id(), bets);
            return stake;
        }

        /** A round row and its bets exactly as given. */
        void insert(UUID round, UUID user, int winningNumber, long totalStake, long totalPayout, long balanceAfter, long stakeEntry,
                    Long payoutEntry, Bet... bets) throws SQLException {
            execute(connection, roundSql(round, user, UUID.randomUUID(), winningNumber, totalStake, totalPayout, balanceAfter, stakeEntry,
                    payoutEntry));
            for (Bet bet : bets) {
                execute(connection, betSql(round, bet.category(), bet.selection(), bet.number(), bet.amount(), bet.payout()));
            }
        }
    }

    @Test
    void aFailedV9LeavesV8AndCanBeRepeated() throws SQLException {
        String schema = migrated("8");
        try (Connection connection = connect(schema)) {
            insertUser(connection, "alice@example.com");
            String before = rows(connection, "users") + rows(connection, "wallets") + rows(connection, "wallet_transactions");
            // Fault injection in this throw-away schema only: a table of the same name makes V9's second
            // CREATE TABLE fail after its function and first table were created in the same transaction.
            execute(connection, "CREATE TABLE roulette_bets (injected INT)");

            assertThatThrownBy(() -> flyway(schema, null).migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("roulette_bets");

            // Rolled back as a whole: no function, no rounds table, V9 not recorded, nothing else changed.
            assertThat(queryString(connection, "SELECT to_regclass('roulette_rounds') IS NULL")).isEqualTo("t");
            assertThat(queryString(connection, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE p.proname = 'reject_row_change' AND n.nspname = current_schema()")).isEqualTo("0");
            assertThat(queryString(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '9'")).isEqualTo("0");
            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("8");
            assertThat(rows(connection, "users") + rows(connection, "wallets") + rows(connection, "wallet_transactions")).isEqualTo(before);

            // Once the cause is gone, the next start applies V9 normally.
            execute(connection, "DROP TABLE roulette_bets");
            flyway(schema, null).migrate();
            assertThat(queryString(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("9");
            assertThat(queryString(connection, "SELECT to_regclass('roulette_bets') IS NOT NULL")).isEqualTo("t");
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
     * Runs a read-only check of {@code infrastructure/neon} under {@code default_transaction_read_only = on}
     * ({@link ReadOnlyScripts}): one result per statement, rows separated by {@code |}, values by {@code ,}.
     */
    private static List<String> readOnlyScript(Connection connection, String file) throws SQLException {
        return ReadOnlyScripts.run(connection, file);
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

    /** A player with a wallet of 1,330 coins: starting entry, a stake of 30 and a payout of 360. */
    private record Ledger(UUID user, UUID wallet, long stake, long payout) {
    }

    private static Ledger ledger(Connection connection, String email) throws SQLException {
        UUID user = insertUser(connection, email);
        UUID wallet = insertWallet(connection, user, 1330);
        insertEntry(connection, wallet, "STARTING_BALANCE", 1000, 1000, "starting-balance");
        long stake = insertEntry(connection, wallet, "ROULETTE_STAKE", -30, 970, "roulette-round:r1:stake");
        long payout = insertEntry(connection, wallet, "ROULETTE_PAYOUT", 360, 1330, "roulette-round:r1:payout");
        return new Ledger(user, wallet, stake, payout);
    }

    private static String roundSql(UUID id, UUID user, UUID request, int winningNumber, long totalStake, long totalPayout,
                                   long balanceAfter, long stakeEntry, Long payoutEntry) {
        return "INSERT INTO roulette_rounds (id, user_id, request_id, winning_number, total_stake, total_payout, balance_after, "
                + "stake_transaction_id, payout_transaction_id) VALUES ('" + id + "', '" + user + "', '" + request + "', "
                + winningNumber + ", " + totalStake + ", " + totalPayout + ", " + balanceAfter + ", " + stakeEntry + ", "
                + (payoutEntry == null ? "NULL" : payoutEntry) + ")";
    }

    private static String betSql(UUID round, String category, String selection, Integer number, long amount, long payout) {
        return "INSERT INTO roulette_bets (round_id, category, selection, number, amount, payout) VALUES ('" + round + "', '"
                + category + "', " + (selection == null ? "NULL" : "'" + selection + "'") + ", "
                + (number == null ? "NULL" : number) + ", " + amount + ", " + payout + ")";
    }

    /**
     * The constraint names of a table, sorted. NOT NULL constraints are left out: PostgreSQL 18 records them
     * in {@code pg_constraint} (type {@code n}), 17 does not.
     */
    private static List<String> constraintNames(Connection connection, String table) throws SQLException {
        return List.of(queryString(connection, "SELECT string_agg(conname, ',' ORDER BY conname COLLATE \"C\") FROM pg_constraint "
                + "WHERE contype <> 'n' AND conrelid = '" + table + "'::regclass").split(","));
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
