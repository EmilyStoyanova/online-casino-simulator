-- Read-only check AFTER the migration (docs/database.md, "Neon").
-- Run: infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-migrations.sql

\conninfo

-- 1. Expected: versions 1–5, all with success = t.
SELECT installed_rank, version, description, type, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- 2. Expected: auth_throttle, flyway_schema_history, interests, spring_session,
--    spring_session_attributes, user_interests, users.
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

-- 3. Expected right after the migration: 8 seeded interests and nothing else
--    (counts only; no row contents are read).
SELECT (SELECT count(*) FROM interests) AS interests,
       (SELECT count(*) FROM users) AS users,
       (SELECT count(*) FROM spring_session) AS sessions,
       (SELECT count(*) FROM auth_throttle) AS throttle_rows;
