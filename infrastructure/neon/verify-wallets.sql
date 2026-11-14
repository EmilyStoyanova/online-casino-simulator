-- Read-only wallet check AFTER V6/V7 (docs/plans/week-03.md §6.7, AC-9.4; docs/database.md, "Neon").
-- Run only within an approved rollout:
--   infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-wallets.sql
-- Counts only; no row contents are read. MigrationCompatibilityIT runs the same statements on
-- PostgreSQL 17 and 18 after the backfill.

\conninfo

-- 1. Expected: 0 in every column.
SELECT
    (SELECT count(*) FROM users u
     WHERE u.role = 'PLAYER' AND NOT EXISTS (SELECT 1 FROM wallets w WHERE w.user_id = u.id))
        AS players_without_wallet,
    (SELECT count(*) FROM wallets w JOIN users u ON u.id = w.user_id WHERE u.role <> 'PLAYER')
        AS wallets_of_non_players,
    (SELECT count(*) FROM wallets w
     WHERE (SELECT count(*) FROM wallet_transactions t WHERE t.wallet_id = w.id AND t.type = 'STARTING_BALANCE') <> 1)
        AS wallets_without_one_starting_entry,
    (SELECT count(*) FROM wallets w
     WHERE w.balance <> (SELECT coalesce(sum(t.amount), 0) FROM wallet_transactions t WHERE t.wallet_id = w.id)
        OR w.balance <> coalesce((SELECT t.balance_after FROM wallet_transactions t WHERE t.wallet_id = w.id
                                  ORDER BY t.id DESC LIMIT 1), 0))
        AS wallets_not_matching_ledger;

-- 2. For the record: players = wallets = starting entries; admins have no wallet.
SELECT
    (SELECT count(*) FROM users WHERE role = 'PLAYER') AS players,
    (SELECT count(*) FROM users WHERE role = 'ADMIN') AS admins,
    (SELECT count(*) FROM wallets) AS wallets,
    (SELECT count(*) FROM wallet_transactions WHERE type = 'STARTING_BALANCE') AS starting_entries;
