# Database

PostgreSQL 17. The schema is owned exclusively by **Flyway**; Hibernate only validates entity
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

## Local database

```bash
docker compose up -d                                  # start
docker exec -it casino-postgres psql -U casino casino # SQL shell
docker compose down -v                                # reset (deletes local data)
```
