# Database

PostgreSQL 17. The schema is owned exclusively by **Flyway**; Hibernate only validates entity
mappings (`spring.jpa.hibernate.ddl-auto=validate`).

## Migration conventions

- Location: `backend/src/main/resources/db/migration`
- Naming: `V<version>__<description>.sql`, e.g. `V2__create_users.sql`
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

## Planned tables (from the implementation plan)

| Table | Purpose | Week |
|---|---|---|
| `users` | Accounts, password hashes, status, role | 2 |
| `user_preferences` | Educational interests and preferences | 2 |
| `wallets` | Current virtual balance and row version (optimistic locking) | 3 |
| `wallet_transactions` | Immutable ledger of balance changes, unique idempotency key | 3 |
| `roulette_rounds` | Rounds, winning number, timestamps | 4 |
| `roulette_bets` | Bet type, amount, payout | 4 |
| `learning_modules` | Educational materials and categories | 7 |
| `quiz_questions` | Questions and correct answers | 8 |
| `quiz_attempts` | Submitted answers and results | 8 |
| `learning_rewards` | Reward conditions and granted rewards | 8 |
| `play_limits` | User-defined limits, breaks, self-exclusion | 9 |
| `user_sessions` | Session history and duration | 9 |
| `audit_events` | Important system and administrative actions | 10 |

## Integrity requirements

1. A user cannot spend more virtual coins than they own.
2. A game round cannot be processed or paid out twice.
3. An educational reward cannot be granted twice for the same eligible completion.
4. Completed transactions are durable and traceable.
5. Only personal data that is strictly necessary is stored.

## Local database

```bash
docker compose up -d                                  # start
docker exec -it casino-postgres psql -U casino casino # SQL shell
docker compose down -v                                # reset (deletes local data)
```
