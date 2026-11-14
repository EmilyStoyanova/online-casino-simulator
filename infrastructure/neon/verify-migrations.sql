-- Read-only schema check (docs/database.md, "Neon"; infrastructure/wallet-rollout.md).
-- Recognises the two states the staging branch can legitimately be in:
--   V5 = before the Week 3 rollout (the state since 2026-11-07),
--   V7 = after it (V6 wallet tables, V7 wallets for existing players).
-- Run: infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-migrations.sql
-- readonly-query.sh wraps it in a READ ONLY transaction that is rolled back. Counts only; no row
-- contents are read. MigrationCompatibilityIT runs these statements (without the psql line below) on
-- PostgreSQL 17 and 18 at V5 and at V7.

\conninfo

-- 1. Where am I? neon_branch_id must equal the staging branch ID shown in Neon Console
--    (it is NULL outside Neon). Stop if it does not.
SELECT current_database() AS database, current_user AS role_name,
       current_setting('server_version') AS server_version,
       current_setting('neon.branch_id', true) AS neon_branch_id;

-- 2. Every applied migration. Expected: success = t in every row; versions 1-5 before the
--    Week 3 rollout, 1-7 after it.
SELECT installed_rank, version, description, type, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- 3. The state in one line. Expected: failed_migrations = 0 and schema_state
--    'V5: before the Week 3 rollout' or 'V7: Week 3 wallet schema'. Anything else: stop.
SELECT max(version::int) FILTER (WHERE success) AS schema_version,
       count(*) FILTER (WHERE NOT success) AS failed_migrations,
       CASE max(version::int) FILTER (WHERE success)
           WHEN 5 THEN 'V5: before the Week 3 rollout'
           WHEN 7 THEN 'V7: Week 3 wallet schema'
           ELSE 'UNEXPECTED: stop and investigate'
       END AS schema_state
FROM flyway_schema_history
WHERE version IS NOT NULL;

-- 4. The tables that this version must have. Expected: present = t in every row. The wallet tables
--    appear only from V6 on.
SELECT t.name, t.since_version, to_regclass(t.name) IS NOT NULL AS present
FROM (VALUES ('flyway_schema_history', 1), ('spring_session', 2), ('spring_session_attributes', 2),
             ('users', 3), ('auth_throttle', 4), ('interests', 5), ('user_interests', 5),
             ('wallets', 6), ('wallet_transactions', 6)) AS t(name, since_version)
WHERE t.since_version <= (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL)
ORDER BY t.since_version, t.name;

-- 5. Expected: 0 (no table that the migrations do not create).
SELECT count(*) AS unexpected_tables
FROM information_schema.tables
WHERE table_schema = current_schema()
  AND table_type = 'BASE TABLE'
  AND table_name NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes', 'users',
                         'auth_throttle', 'interests', 'user_interests', 'wallets', 'wallet_transactions');

-- 6. Counts. Before the rollout: players is the number of wallets V7 will open (admins get none),
--    and wallets/ledger_entries are NULL (the tables do not exist yet). After it: wallets = players
--    and ledger_entries >= players. interests = 8.
SELECT (SELECT count(*) FROM interests) AS interests,
       (SELECT count(*) FROM users WHERE role = 'PLAYER') AS players,
       (SELECT count(*) FROM users WHERE role = 'PLAYER' AND status = 'DISABLED') AS disabled_players,
       (SELECT count(*) FROM users WHERE role = 'ADMIN') AS admins,
       (SELECT count(*) FROM spring_session) AS sessions,
       (SELECT count(*) FROM auth_throttle) AS throttle_rows,
       CASE WHEN to_regclass('wallets') IS NOT NULL
            THEN (xpath('/row/n/text()', query_to_xml('SELECT count(*) AS n FROM wallets', false, true, '')))[1]::text::bigint
       END AS wallets,
       CASE WHEN to_regclass('wallet_transactions') IS NOT NULL
            THEN (xpath('/row/n/text()', query_to_xml('SELECT count(*) AS n FROM wallet_transactions', false, true, '')))[1]::text::bigint
       END AS ledger_entries;
