-- TEST ONLY (test classpath, never packaged; docs/plans/week-03.md §6.2, W3-04).
--
-- The production schema (V6) allows only STARTING_BALANCE, a credit. The first real debit type comes
-- with the game in Week 4, but the wallet's credit and debit rules, idempotency and concurrency must be
-- tested against the real schema now. This Flyway callback runs after every migration of a test
-- database and widens the type list by two test types, each with its own direction rule:
--   TEST_CREDIT  amount > 0
--   TEST_DEBIT   amount < 0
-- Every other V6 constraint (amount and balance bounds, STARTING_BALANCE direction, key format,
-- uniqueness, foreign keys) and both triggers stay exactly as in production. MigrationCompatibilityIT
-- migrates without this callback and proves that the production schema rejects both test types on
-- PostgreSQL 17 and 18. Idempotent, because Flyway runs callbacks after every migrate.

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS ck_wallet_transactions_type;
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_type CHECK (type IN ('STARTING_BALANCE', 'TEST_CREDIT', 'TEST_DEBIT'));

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS ck_wallet_transactions_test_direction;
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_test_direction
        CHECK ((type <> 'TEST_CREDIT' OR amount > 0) AND (type <> 'TEST_DEBIT' OR amount < 0));
