-- Read-only check BEFORE the first migration (docs/database.md, "Neon").
-- Run: infrastructure/neon/readonly-query.sh < infrastructure/neon/preflight.sql

-- 1. Where am I? Expected: your staging branch's host, the database and role from .env.neon,
--    and an SSL connection (psql only connects with a verified certificate).
\conninfo
SELECT current_database() AS database, current_user AS role_name,
       current_setting('server_version') AS server_version;

-- 2. Expected: 0 rows (an empty database: no tables, views or sequences outside the system schemas).
SELECT n.nspname AS schema, c.relname AS name, c.relkind AS kind
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY 1, 2;

-- 3. Expected: f (no Flyway history yet) and t (the role may create the tables in public).
SELECT to_regclass('public.flyway_schema_history') IS NOT NULL AS flyway_history_exists,
       has_schema_privilege('public', 'CREATE') AS can_create_in_public;
