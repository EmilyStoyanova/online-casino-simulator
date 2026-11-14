-- V7: Wallets for PLAYER accounts that existed before wallets did (docs/plans/week-03.md §6.6, P-W3-11).
--
-- Since W3-05 a new player's wallet is opened at registration (WalletOpener). Accounts created before
-- V6 (or between V6 and this migration on a developer database) have none. Each such PLAYER, active or
-- disabled, gets exactly what registration gives: one wallet holding 1000 coins and one STARTING_BALANCE
-- ledger entry with the idempotency key 'starting-balance'. ADMIN accounts never get a wallet.
--
-- * The amount is the literal 1000 on purpose: the migration gives the same result wherever it runs,
--   whatever app.wallet.starting-balance (APP_WALLET_STARTING_BALANCE) is set to.
-- * One statement: the entries are inserted for exactly the wallets this statement creates (RETURNING),
--   so wallets that already exist and their entries are never touched, and a wallet can never be left
--   without its entry. Flyway also runs the migration in one transaction.
-- * Idempotent by itself: players who already have a wallet are skipped (NOT EXISTS, and ON CONFLICT for
--   a wallet inserted concurrently), so running the statement again inserts nothing.
-- * No constraint or trigger is changed: the rows satisfy every V6 rule (balance = sum of entries =
--   balance_after, positive STARTING_BALANCE, valid key), and the ledger stays append-only.
-- * created_at of the new wallets and entries is the migration time, not the registration time.

WITH new_wallets AS (
    INSERT INTO wallets (user_id, balance)
    SELECT u.id, 1000
    FROM users u
    WHERE u.role = 'PLAYER'
      AND NOT EXISTS (SELECT 1 FROM wallets w WHERE w.user_id = u.id)
    ON CONFLICT (user_id) DO NOTHING
    RETURNING id
)
INSERT INTO wallet_transactions (wallet_id, type, amount, balance_after, idempotency_key)
SELECT id, 'STARTING_BALANCE', 1000, 1000, 'starting-balance'
FROM new_wallets;
