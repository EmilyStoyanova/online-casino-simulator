# Database

PostgreSQL 17 locally (Docker Compose, Testcontainers); the Neon project runs PostgreSQL 18 (see [Neon](#neon)). The schema is owned exclusively by **Flyway**; Hibernate only validates entity
mappings (`spring.jpa.hibernate.ddl-auto=validate`).

## Migration conventions

- Location: `backend/src/main/resources/db/migration`
- Naming: `V<version>__<description>.sql`, e.g. `V3__create_users.sql`. Versions follow the order of
  implementation: Flyway rejects a new migration whose version is lower than one already applied.
- Applied migrations are **never edited**; every change is a new migration.
- Each table is introduced by the module (and week) that owns it.
- Conventions for new tables:
  - `snake_case` plural table names, `id` primary key (`uuid` or `bigint identity`, decided per table)
  - `created_at timestamptz not null default now()`; mutable tables also get `updated_at` with the
    `set_updated_at()` trigger
  - Monetary-like values (virtual coins) as `bigint` — never floating point
  - Business invariants as database constraints where possible (`CHECK (balance >= 0)`, unique
    idempotency keys) in addition to application checks

## Applied migrations

| Version | Description |
|---|---|
| V1 | `set_updated_at()` trigger function shared by tables with an `updated_at` column |
| V2 | `spring_session`, `spring_session_attributes`: HTTP sessions (official Spring Session JDBC 4.1.1 schema, verbatim) |
| V3 | `users`: accounts (UUID id, normalised unique email, encoded password hash, `role`, `status`, 18+ confirmation, timestamps, `version`) with `set_updated_at()` trigger |
| V4 | `auth_throttle`: fixed-window counters for login/registration throttling; keys are `<scope>:<HMAC-SHA-256 hex>` with the secret `APP_SECURITY_THROTTLE_SECRET` (no raw email or IP, enforced by a `CHECK`); rows are deleted hourly a day after their window ended |
| V5 | `interests`: catalogue of educational interests (stable upper-case `code` as key, `sort_order`, `active`), seeded with 8 fixed entries; `user_interests`: each user's selection, replacing the plan's use of `user_preferences` for interests (primary key `(user_id, interest_code)`, cascade on user deletion, interests referenced by users cannot be deleted, only deactivated) |

Flyway records every applied migration in `flyway_schema_history`. `GET /api/v1/system/status`
reports the latest version as `schemaVersion` (`5`).

## Current schema (after V5)

```mermaid
erDiagram
    users ||--o{ user_interests : "selects (ON DELETE CASCADE)"
    interests ||--o{ user_interests : "is selected (delete rejected)"
    spring_session ||--o{ spring_session_attributes : "has (ON DELETE CASCADE)"
    users ||..o{ spring_session : "principal_name = users.id (no foreign key)"
```

`auth_throttle` stands alone: its keys are HMACs, so it does not reference `users`.

### Users and roles (`users`, V3)

| Column | Notes |
|---|---|
| `id` | `UUID`, generated (`gen_random_uuid()`), so account ids cannot be enumerated |
| `email` | `VARCHAR(254)`, unique (`uq_users_email`), stored trimmed and lower-cased (`ck_users_email_normalized`) |
| `password_hash` | `VARCHAR(100)`, only an encoded hash with the `{id}` prefix, e.g. `{bcrypt}$2a$12$…` (`ck_users_password_hash_encoded`) |
| `display_name` | `VARCHAR(30)` |
| `role` | `PLAYER` (default) or `ADMIN` (`ck_users_role`); one role per account |
| `status` | `ACTIVE` (default) or `DISABLED` (`ck_users_status`); disabled accounts cannot log in |
| `adult_confirmed_at` | when the 18+ confirmation was given (server clock); no date of birth is stored |
| `last_login_at` | set at each successful login |
| `created_at`, `updated_at` | `updated_at` maintained by the `trg_users_updated_at` trigger (`set_updated_at()`, V1) |
| `version` | optimistic locking (`409 CONCURRENT_MODIFICATION`) |

Registration always creates a `PLAYER`. An `ADMIN` is created only by the start-up bootstrap
(`APP_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`). Week 2 has no feature that changes a role or a status.

### Interests (`interests`, `user_interests`, V5)

- `interests`: reference data maintained only by migrations. Columns: `code` (primary key,
  `^[A-Z][A-Z0-9_]*$`), `name`, `description`, `sort_order`, `active`. Eight entries are seeded.
  `GET /api/v1/interests` lists the active ones by `sort_order`, then `code`.
- `user_interests`: a user's selection. The primary key is `(user_id, interest_code)`, so there are
  no duplicates. Deleting a user deletes its rows; an interest that is still referenced cannot be
  deleted, only deactivated. The limit of 8 per user and the rejection of inactive codes are
  enforced by the application.

### Session persistence (`spring_session`, `spring_session_attributes`, V2)

- The official Spring Session JDBC schema. Hibernate and Spring Session do not create it
  (`spring.session.jdbc.initialize-schema=never`).
- One row per HTTP session. `session_id` is the value of the session cookie, so these rows are as
  sensitive as passwords: never copy them into tickets or logs. `principal_name` holds the **user
  id** of a signed-in session and is indexed (`spring_session_ix3`) for revocation.
- The attributes hold the Spring Security context (user id and role, never the password hash) and
  `com.casino.auth.service.SessionSignInService.AUTHENTICATED_AT` (sign-in time) for the 12-hour
  absolute lifetime.
- Logout deletes the row. Expired sessions are deleted by Spring Session's periodic cleanup job.
  Sessions survive backend restarts.

### Authentication throttling (`auth_throttle`, V4)

- One row per throttle key: `throttle_key` (`<SCOPE>:<64 hex>`, enforced by
  `ck_auth_throttle_key_hashed`), `attempt_count` (> 0) and `window_started_at` (indexed for the
  cleanup).
- Scopes: `LOGIN_ACCOUNT_IP` (5 failed logins for one account from one IP per 15 min), `LOGIN_IP`
  (50 failed logins from one IP per 15 min) and `REGISTER_IP` (10 registration attempts from one IP
  per hour, whatever their outcome). Limits: `app.security.throttle.*` in `application.yml`.
- The key is an HMAC of the normalised email and/or client IP (see
  [Throttling secret](#throttling-secret)), so the table contains no email or IP address.

## Planned tables (from the implementation plan)

The Week 2 design is in [`docs/plans/week-02.md`](plans/week-02.md) §6. Deviations from the
original plan are listed in
[`docs/proposals/0001-implementation-plan-alignment.md`](proposals/0001-implementation-plan-alignment.md).

| Table | Purpose | Week |
|---|---|---|
| `user_preferences` | UI preferences (sound, reduced motion, …) | 6 |
| `wallets` | Current virtual balance and row version (optimistic locking) | 3 |
| `wallet_transactions` | Immutable ledger of balance changes, unique idempotency key | 3 |
| `roulette_rounds` | Rounds, winning number, timestamps | 4 |
| `roulette_bets` | Bet type, amount, payout | 4 |
| `learning_modules` | Educational materials and categories | 7 |
| `quiz_questions` | Questions and correct answers | 8 |
| `quiz_attempts` | Submitted answers and results | 8 |
| `learning_rewards` | Reward conditions and granted rewards | 8 |
| `play_limits` | User-defined limits, breaks, self-exclusion | 9 |
| `user_sessions` | Play-session history and duration (not HTTP sessions) | 9 |
| `audit_events` | Important system and administrative actions | 10 |

## Integrity requirements

1. A user cannot spend more virtual coins than they own.
2. A game round cannot be processed or paid out twice.
3. An educational reward cannot be granted twice for the same eligible completion.
4. Completed transactions are durable and traceable.
5. Only personal data that is strictly necessary is stored.

## Throttling secret

`auth_throttle.throttle_key` is an HMAC-SHA-256 of the scope and the normalised email and/or client
IP, keyed with `APP_SECURITY_THROTTLE_SECRET` (Base64, at least 32 random bytes; the backend does not
start without a valid value). Without the secret a row cannot be linked to an email or IP address.

- **Local:** generate your own value with `openssl rand -base64 32` and put it in `.env`. Tests use a
  random secret per run and need none.
- **Production:** a separate value, kept only in the hosting provider's secret store; never in Git,
  logs, tickets or chat.

**Effect of changing the secret.** Every existing row becomes unreachable: new attempts produce new
keys, so all throttle counters start again at zero, once. Nothing breaks and no migration is needed.
The orphaned rows are deleted by the hourly cleanup at most 1 h (longest window) + 1 day later.

**Rotation (production):**
1. Rotate only when needed: suspected exposure of the secret (or of a database backup together with
   it) or a staff change with access to it. Routine rotation brings no benefit here, because the rows
   are short-lived.
2. Generate a new value, replace it in the secret store, and restart or redeploy **all** instances
   together. During a rolling deploy, instances with different secrets keep separate counters, so the
   effective limits are briefly higher.
3. Prefer a quiet period: for up to 15 min (1 h for registration) an attacker gets a fresh attempt
   budget.
4. Optionally run `DELETE FROM auth_throttle;` right after the switch, instead of waiting for the
   cleanup.

## Profile data (W2-10)

- `PATCH /api/v1/users/me` changes only `users.display_name` and the user's rows in
  `user_interests`, in one transaction. The primary key prevents duplicate selections; the limit of
  8 and the rejection of unknown or inactive codes are checked by the application. Interests that
  stay selected keep their row (`created_at`).
- Every profile change increments `users.version` (optimistic locking), also a change of the
  interests only. `last_login_at` is set without a new version, so logins never conflict with a
  profile update; updates write only the changed columns, so neither overwrites the other.
- To withdraw an interest, set `interests.active = false` (a migration). Users who selected it no
  longer see it in their profile; their row is removed at their next change of the selection.
- Manual changes of `users` in the database bypass these rules. Follow the
  [session revocation runbook](#session-revocation-runbook) after such a change.

## Session revocation runbook

**Why:** a session keeps the role and status that the account had at sign-in. Week 2 has no feature
that disables a user or changes a role or password, so nothing revokes sessions automatically
(`docs/plans/week-02.md` §7.5; mandatory with the admin features in Week 10). Without revocation, a
session after a manual change lasts at most 30 minutes idle or 12 hours in total.

**When:** after every manual change in the database that deletes an account, disables it
(`status = 'DISABLED'`), changes its `role` or replaces its `password_hash`.

**Steps** (in `psql`, see [Local database](#local-database)):

1. Find the user id:

   ```sql
   SELECT id, role, status FROM users WHERE email = lower('alice@example.com');
   ```

2. Make the change, then delete all sessions of that user in the same transaction:

   ```sql
   BEGIN;
   UPDATE users SET status = 'DISABLED' WHERE id = '<user id>';   -- the manual change
   DELETE FROM spring_session WHERE principal_name = '<user id>'; -- attributes are deleted by cascade
   COMMIT;
   ```

3. Check: `SELECT count(*) FROM spring_session WHERE principal_name = '<user id>';` returns `0`. The
   user's next request gets `401 SESSION_EXPIRED`.

## Local database

```bash
docker compose up -d       # start (repository root)
docker compose ps          # state and health
docker compose stop        # stop, keep the data
docker exec -it casino-postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB>   # SQL shell (both "casino" in .env.example)
```

`docker compose down -v` **deletes the volume with all local data** (accounts, sessions). Use it
only as a deliberate reset, not as a routine fix.

## Neon

The local Docker database stays the default. The backend connects to Neon (managed PostgreSQL 18,
project `online-casino-simulator`, AWS Europe Central 1) only when the Spring profile `neon` is
active. Nothing is copied between the two: Flyway creates an empty schema in Neon, and no local
accounts, passwords or sessions go there.

| | Local (default) | Neon (profile `neon`) |
|---|---|---|
| Activated by | — | `SPRING_PROFILES_ACTIVE=neon` |
| Values from | `.env` | `.env.neon` (git-ignored, template `.env.neon.example`); everything else still from `.env` |
| Connection variables | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `NEON_DB_HOST`, `NEON_DB_NAME`, `NEON_DB_USERNAME`, `NEON_DB_PASSWORD` |
| TLS | none (localhost only) | `verify-full` (certificate and host name) plus SCRAM channel binding |
| Connection pool | 10 connections, 5 s timeout | 5 connections, 15 s timeout (compute wake-up) |
| Configuration | `application.yml` | `application-neon.yml` (overrides only the data source) |

**Current state (2026-11-07):**

| Branch | State |
|---|---|
| `staging` | V1–V5 applied with the procedure below: 7 tables, 8 seeded interests, no accounts or sessions |
| `production` | Empty and unused until the deployment (Week 12) |

**Version difference:** Neon runs PostgreSQL 18.6, while the local database and the Testcontainers
integration tests use 17.11.
- Flyway 12.4.0 supports major versions up to 18, and the migrations use only features available
  in both versions.
- Integration tests still do not prove behaviour on 18. Aligning both on one major version is open
  for the deployment review (Week 12).

To check which branch `.env.neon` points to, compare `neon.branch_id` with the branch ID on the
branch's page in the Console:

```sql
SELECT current_setting('neon.branch_id'), current_setting('neon.endpoint_id');
```

The pre-flight SQL alone does not identify the branch.

### Connection configuration

[`application-neon.yml`](../backend/src/main/resources/application-neon.yml) builds the JDBC URL
from the variables:

```text
jdbc:postgresql://${NEON_DB_HOST}/${NEON_DB_NAME}?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require
```

- **Direct endpoint, not the pooler.** Neon's pooled hosts (`-pooler`) run PgBouncer in
  transaction mode, which does not support session-level advisory locks, and Flyway takes one per
  migration run. Neon recommends direct connections for migrations. Flyway and Spring Session JDBC
  use the same direct connection; a 0.25 CU compute allows 97 application connections, and the
  pool uses at most 5. If app traffic ever needs the pooler, Flyway must keep a direct URL
  (`spring.flyway.url`); that is a deployment decision (Week 12).
- **`sslmode=verify-full`** checks the certificate chain and that the certificate matches the host
  name. Neon uses a Let's Encrypt certificate (ISRG Root X1).
- **`DefaultJavaSSLFactory`** validates the chain against the JVM trust store, which already
  contains ISRG Root X1 (JDK 21). The driver's default factory would instead require a
  `root.crt` file in `%APPDATA%\postgresql\`. The host name check is done by the driver for
  `verify-full` whichever factory is used.
- **`channelBinding=require`**: SCRAM-SHA-256-PLUS binds the login to the TLS channel (supported
  by the driver 42.7.14 that Spring Boot 4.1.2 manages).
- **Credentials are separate properties**, never in the URL. Flyway logs the URL at start-up, so it
  shows the host and database name, never the user or password. Do not use Neon's ready-made Java
  connection string: it contains `user=` and `password=`.
- **Values are not interchangeable with the local ones.** Without the `NEON_DB_*` values the
  backend does not start: the connection fails with `UnknownHostException: ${NEON_DB_HOST}`. It
  never falls back to the local database.

### One-time setup (Neon Console)

1. **Create a branch for the first tests** instead of using `production`: **Branches → Create
   branch**, name `staging`, parent `production`. The branch has its own compute and the same
   database `neondb` and roles. `production` stays unused until the deployment (Week 12).
2. **Find the connection values:** on the dashboard, **Connect**, then choose branch `staging`,
   database `neondb`, the role (for example `neondb_owner`) and switch **Connection pooling off**.
   You need the host (`ep-….eu-central-1.aws.neon.tech`, without `-pooler`), the database, the role
   and the password.
3. **Store them privately in `.env.neon`** (repository root). Type or paste the values in your
   editor, never in a chat, a prompt or a shell command:

   ```bash
   cp .env.neon.example .env.neon
   code .env.neon                  # or any editor; values without quotes
   git check-ignore -v .env.neon   # must print the .gitignore rule `.env.*`
   grep -c '^NEON_DB_[A-Z]*=.' .env.neon   # 4 = all four values set (prints only the count)
   ```

### Migration procedure

Flyway applies migrations automatically when the backend starts with the profile, so the backend is
started against Neon only after the checks below.

1. **Read-only pre-flight check.**
   [`readonly-query.sh`](../infrastructure/neon/readonly-query.sh) runs SQL with `psql` from the
   PostgreSQL Docker image (no local `psql` needed). It works like this:
   - credentials go from `.env.neon` into the container environment only;
   - TLS is `verify-full` with channel binding;
   - everything runs in one `READ ONLY` transaction that is rolled back.

   ```bash
   bash infrastructure/neon/readonly-query.sh < infrastructure/neon/preflight.sql
   ```

   Expected:
   - `\conninfo` names the `staging` endpoint's host, database `neondb` and an SSL connection;
   - **no tables** outside the system schemas;
   - `flyway_history_exists = f` and `can_create_in_public = t`.

   **Stop if anything else appears**: it is the wrong branch or database.
2. **What will run:** V1–V5 from [Applied migrations](#applied-migrations), in this order:
   - V1: `set_updated_at()`;
   - V2: `spring_session` and `spring_session_attributes`;
   - V3: `users`;
   - V4: `auth_throttle`;
   - V5: `interests` (8 seeded rows) and `user_interests`.

   Flyway adds `flyway_schema_history`. These Flyway defaults are safety nets:
   - Flyway refuses a non-empty schema without history, because `baseline-on-migrate` is off;
   - `clean` is disabled, so Flyway never drops anything.
3. **Migrate** (only after the pre-flight check matches). From `backend/`, on a port that does not
   clash with the local backend:

   ```bash
   SPRING_PROFILES_ACTIVE=neon SERVER_PORT=8081 ./mvnw spring-boot:run
   ```

   Expected log:
   - `The following 1 profile is active: "neon"`;
   - `Database: jdbc:postgresql://ep-…/neondb… (PostgreSQL 18.…)`;
   - `Successfully applied 5 migrations to schema "public", now at version v5`;
   - `Started CasinoSimulatorApplication`.

   http://localhost:8081/api/v1/system/status then reports `schemaVersion` `5`. Stop with
   `Ctrl+C`. Do not set `APP_BOOTSTRAP_ADMIN_*` for this run, and do not register accounts on the
   branch unless you mean to.
4. **Verify (read-only):**

   ```bash
   bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-migrations.sql
   ```

   Expected:
   - versions 1–5, each with `success = t`;
   - the tables `auth_throttle`, `flyway_schema_history`, `interests`, `spring_session`,
     `spring_session_attributes`, `user_interests` and `users`;
   - 8 interests and no users, sessions or throttle rows.
5. **Later starts** validate the checksums of the applied migrations and apply only new ones.

### Browsing the tables in Neon Console

Open project `online-casino-simulator` and select branch **`staging`** in the branch selector.
**Tables** (left navigation) lists the tables of database `neondb`, schema `public`:
- `flyway_schema_history` shows the applied versions;
- `interests` shows the 8 seeded entries.

The **SQL Editor** (same branch and database) runs queries as the selected role, with write access,
so use it for `SELECT` only. Do not open `spring_session` rows: session ids are as sensitive as
passwords.

### Troubleshooting Neon connections

| Symptom | Cause and fix |
|---|---|
| `UnknownHostException: ${NEON_DB_HOST}` | The profile is active but no value was found: `.env.neon` is missing, is not in the repository root, or the backend was started outside `backend/` or the root. |
| `UnknownHostException: ep-…` | Typo in `NEON_DB_HOST`, or the branch or endpoint was deleted. Copy the host again from **Connect**. |
| `password authentication failed for user` | Wrong role or password, quotes around a value, or the password was reset in the Console. Re-copy the values into `.env.neon`. |
| `PKIX path building failed` / `SSLHandshakeException` | The certificate chain cannot be verified. Usual causes are an old JDK without ISRG Root X1, or antivirus or proxy software that intercepts TLS. Use JDK 21 and allow the connection. **Never lower `sslmode`** to get around it. |
| `The hostname … could not be verified` | `NEON_DB_HOST` is not the endpoint name from **Connect** (for example an IP address or an alias). |
| `channel binding required but not supported` | Something between you and Neon terminates TLS (corporate proxy). Use another network; do not disable channel binding. |
| Connection timeout / `Couldn't connect to compute node` | The compute is waking up from scale-to-zero (retry after a few seconds), or the network blocks outbound port 5432. Check the compute state on the branch page, and try another network. |
| `terminating connection due to administrator command` | The compute was suspended while a pooled connection was idle. The pool replaces the connection; retry the request. |
| `Found non-empty schema(s) "public" without schema history table` | The database is not empty. **Stop**: it is probably the wrong branch or database. Never enable `baseline-on-migrate` to get past it. |
| `Validate failed: Migrations have failed validation` | An applied migration file was changed locally. Restore it; changes go into a new migration. |
| `readonly-query.sh` prints a validation message | It names the problem (missing file, empty value, quotes, `-pooler` host) without showing any value. |
