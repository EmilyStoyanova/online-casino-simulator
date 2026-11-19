# Week 3 rollout: virtual wallet on staging (V6, V7)

This runbook brings the Week 3 wallet (Flyway **V6** and **V7**, `GET /api/v1/wallet`,
`GET /api/v1/wallet/transactions`) to the staging environment:

```
Vercel (frontend, auto-deploys main) ──/api──▶ Render staging backend ──▶ Neon branch staging (V5 before, V7 after)
```

**Status: executed on 2026-11-15** with the project owner's approval; see the
[rollout record](#rollout-record-2026-11-15) below. The steps after it are kept unchanged as the
historical procedure and as the template for later migrations (Week 4: `docs/plans/week-04.md` §15
and the prepared, not yet executed [roulette-rollout.md](roulette-rollout.md)).
Neon `production` is never touched: every command here uses the `staging` branch only.

## Rollout record (2026-11-15)

| Step | Result |
|---|---|
| Merge | Week 3 merged into `main` (PR #9, merge commit `b07e656`), CI green |
| Restore point | Neon branch `staging-before-week-03` created from `staging` at V5 before the deploy. **Kept**; it is not deleted without a separate approval |
| Deploy | Manual Render deploy of `main` (Auto-Deploy stays Off); the backend started successfully |
| Migration | Flyway applied V6 and V7 to Neon `staging` at start-up: V5 → **V7** |
| Backfill (V7) | The one existing `PLAYER` received a wallet with **1,000** coins and one `STARTING_BALANCE` entry |
| `GET /api/v1/wallet` | `200`, `balance` 1000 |
| `GET /api/v1/wallet/transactions` | `200`, one `STARTING_BALANCE` entry |
| Read-only integrity checks (`verify-wallets.sql`) | **0** violations |
| Registration | Still closed (`APP_REGISTRATION_ENABLED=false`) |
| Neon `production` | Untouched |

No rollback was needed. Later migrations follow their own approved runbook; they do not re-run V6
or V7.

**Approval.** Each step marked **APPROVAL** changes a shared system and needs an explicit,
separate go-ahead from the project owner at the time it is done. Read-only steps (marked
read-only) change nothing.

## What happens, in short

1. The Week 3 branch is merged into `main` (**APPROVAL**, pull request with green CI).
2. Vercel deploys `main` automatically. The frontend is unchanged by Week 3, so that deploy changes
   nothing visible.
3. A manual Render deploy of `main` (**APPROVAL**; Auto-Deploy is Off) starts the new backend.
4. At start-up, Flyway on the new instance applies **V6** (wallet tables) and then **V7** (one
   wallet with 1,000 coins and one `STARTING_BALANCE` entry for every existing `PLAYER`, none for
   admins), each in its own transaction, before the application accepts requests.
5. Read-only checks confirm the result.

**Database changes are forward-only.** Rolling the application back on Render does **not** undo V6
or V7: the tables and the wallets stay. The rollback options are described in
[Rollback](#rollback).

## Preconditions (all must hold before step 1)

| # | Check | How |
|---|---|---|
| P1 | The Week 3 review is complete and the pull request is approved | GitHub |
| P2 | CI is green on the pull request: backend `verify` (including PostgreSQL 17/18 migration tests and the JaCoCo check) and the frontend checks | GitHub Actions |
| P3 | `.env.neon` points to the **staging** branch's direct endpoint | read-only step 1 below |
| P4 | Render: `APP_REGISTRATION_ENABLED` is `false` (or unset with profile `render`) and stays so for the whole rollout | Render → service → Environment (view only) |
| P5 | Render: Auto-Deploy is **Off**, branch `main`, profiles `neon,render` | Render → service → Settings (view only) |
| P6 | Render: `APP_WALLET_STARTING_BALANCE` and `APP_WALLET_LOCK_TIMEOUT` are **not** set (defaults 1000 and 5s) | Render → Environment (view only) |
| P7 | A quiet time window; nobody else deploys during it | team |

Why registration must stay closed: during a Render deploy the **old** instance keeps serving until
the new one is healthy. The old code knows nothing about wallets, so a player registered by it after
V7 committed would have no wallet. With registration closed, no account can be created at all.

## Read-only preflight

All commands from the repository root, in Git Bash, with Docker running. `readonly-query.sh` runs
everything in a `READ ONLY` transaction that is rolled back; credentials come from `.env.neon` and
are never printed.

**1. Location and state (read-only).**

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-migrations.sql
```

Expected:
- `\conninfo`: the staging endpoint host (no `-pooler`), database `neondb`, an SSL connection;
- `neon_branch_id` equals the **staging** branch ID in Neon Console → Branches → `staging`
  (**stop** if it is the production branch or anything else);
- every row of the migration history has `success = t`, versions 1–5;
- `schema_state` = `V5: before the Week 3 rollout`, `failed_migrations` = 0;
- every expected table `present = t`, `unexpected_tables` = 0;
- the counts line: `interests` = 8, `wallets` and `ledger_entries` empty (NULL).

**2. Record the counts (read-only).** Write down `players`, `disabled_players`, `admins` and
`sessions` from the last line. After the rollout, `wallets` must equal `players`, and V7 will have
created exactly that many wallets and starting entries. Admins get none.

**3. Stop conditions.** Do not continue if any expectation differs: a wrong branch, a failed or
unexpected migration, a schema version other than 5, or unexpected tables. Investigate first.

## Backup and restore point (APPROVAL)

Neon restores by branching, not by dump files. Before the deploy, create a restore point of
`staging` in Neon Console:

1. Neon Console → project `online-casino-simulator` → **Branches** → `staging` → **Create branch**
   (or *Create child branch*), name `staging-before-week-03`, from **current point in time**.
   This is instant (copy-on-write) and does not touch `staging` itself. **APPROVAL** (it creates a
   resource in the Neon project).
2. Do not connect any application to the backup branch; it only preserves the V5 state.
3. Also note the current time (UTC): Neon's *restore to a point in time* can use it within the
   project's history retention window. Check the window in the Console (it is short on the Free
   plan); the backup branch does not depend on it.
4. Delete the backup branch only after the rollout has been accepted (**APPROVAL**), to stay
   within the plan's branch limit.

## Deployment

**4. Merge (APPROVAL).** Merge the approved Week 3 pull request into `main`. Vercel then deploys
`main` automatically; the frontend bundle has no Week 3 change.

**5. Deploy on Render (APPROVAL).** Render → service → **Manual Deploy → Deploy latest commit**
(the merge commit). Nothing else is changed in Render: no environment variable, no setting.

**6. Watch the log.** The build takes a few minutes, the start on the Free instance about 2 more.
Expected, in this order:

```
The following 2 profiles are active: "neon", "render"
Registration is closed (APP_REGISTRATION_ENABLED=false): POST /api/v1/auth/register answers 403
Current version of schema "public": 5
Migrating schema "public" to version "6 - create wallets"
Migrating schema "public" to version "7 - backfill existing player wallets"
Successfully applied 2 migrations to schema "public", now at version v7 (execution time …)
Started CasinoSimulatorApplication
```

(Message formats as logged by Flyway 12.4 in the integration tests; timestamps and timings differ.)
**Stop the service** (Render → Suspend, **APPROVAL**) if the log shows migrations other than V6 and
V7 being applied, or `Current version of schema "public": << Empty Schema >>`: it is pointing at a
different database.

How Flyway behaves here:
- It runs once, in the new instance, before the web server accepts requests. The Free plan has
  no pre-deploy command; migration at start-up is the configured mechanism.
- It validates the checksums of V1–V5 first; a changed file stops the start.
- V6 and V7 run in **separate transactions**. A failure rolls back that migration only and stops
  the start-up: V6 failing leaves V5; V7 failing leaves V6 without wallets. Nothing is half-applied
  within a migration.
- If the new instance does not become healthy, Render keeps the old instance serving. The old code
  runs on a V6 or V7 schema unchanged (it does not map the new tables), but it has no wallet API.
- Flyway's advisory lock prevents two instances from migrating at the same time.

## Post-migration checks (read-only)

**7. Schema and data (read-only).**

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-migrations.sql
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-wallets.sql
```

Expected:
- `verify-migrations.sql`: versions 1–7 all `success = t`; `schema_state` = `V7: Week 3 wallet
  schema`; `wallets` and `wallet_transactions` present; `unexpected_tables` = 0; `players`,
  `disabled_players`, `admins` as recorded in step 2; `wallets` = `players`; `ledger_entries` =
  `players`;
- `verify-wallets.sql`: every column of the first result `0` (no player without a wallet, no wallet
  of a non-player, every wallet with exactly one starting entry, every balance equal to its
  ledger); second result `players` = `wallets` = `starting_entries`.

**8. API (read-only for data).** `B=https://online-casino-simulator.onrender.com`; the Vercel
origin `https://online-casino-simulator.vercel.app` gives the same answers through its rewrite.

```bash
curl -s --max-time 300 $B/actuator/health/readiness            # {"status":"UP"}
curl -s $B/api/v1/system/status                                # "schemaVersion":"7"
curl -s -o /dev/null -w '%{http_code}\n' $B/api/v1/wallet                     # 401
curl -s -o /dev/null -w '%{http_code}\n' $B/api/v1/wallet/transactions        # 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST $B/api/v1/wallet             # 403 (CSRF before anything else)
```

Then repeat the security regression checks and the closed-registration probe of
[render/README.md → Verify after a deploy](render/README.md#verify-after-a-deploy).

**9. Authenticated wallet checks.** They need a signed-in account; signing in writes a session row
and `last_login_at`, so each option needs **APPROVAL**:
- an existing staging **player** (if step 2 counted one): sign in through the Vercel frontend, then
  in the same browser open `/api/v1/wallet` (`200`, `balance` 1000 for a backfilled player) and
  `/api/v1/wallet/transactions` (`200`, one `STARTING_BALANCE` entry, `"nextCursor":null`);
- an existing **admin**: the same URLs answer `403 ACCESS_DENIED`;
- **no player exists:** registration would have to be opened briefly
  ([render/README.md → Registrations](render/README.md#registrations-on-a-public-url)), one test
  player registered (its wallet comes from `WalletOpener`, not from V7), checked as above, and
  registration closed again. That changes an environment variable and redeploys twice, so it is a
  separate decision; skipping it is acceptable, because the integration tests cover the flow.

Session cookies are never copied into tickets, chats or commands.

## Success, failure and rollback

**Success** (all of them):
- the log shows V6 and V7 applied and the application started;
- every read-only check of steps 7 and 8 matches;
- step 9, if approved, matches;
- no `5xx` in the Render log during the first minutes, apart from the documented cold start.

**Failure** (any of them): the start-up fails; a read-only check shows a player without a wallet,
an inconsistent balance or an unexpected table; `schemaVersion` is not 7; wallet endpoints answer
anything but the expected status.

### Rollback

Decide with the project owner; every option below is an **APPROVAL** step.

| Situation | Action | Effect |
|---|---|---|
| The new instance never became healthy, schema at V5 (V6 failed) | Nothing to undo in the database. Fix the cause in a new commit; deploy again later | Old instance keeps serving |
| Schema at V6, V7 failed | Fix the cause in a new commit. The next start applies V7 (it was not recorded) | Old instance keeps serving; no wallets yet |
| V6 and V7 applied, the new application misbehaves | **Application rollback:** Render → Deploys → previous deploy → *Rollback*. The schema stays at V7; the old code ignores the wallet tables | Wallet API gone, data kept. Re-deploying the fixed build later needs no migration |
| A player without a wallet after the deploy (registered by the old instance) | Do **not** insert rows by hand. A one-off `WalletService.openWallet` run is a separate decision (`docs/plans/week-03.md` §6.6) | Fixes only that account |
| The data itself is wrong (a check of step 7 fails) and must be reverted | **Database restore:** point the service at a branch restored from `staging-before-week-03` (or restore `staging` to the noted time in Neon Console), then roll the application back as above | Everything written to `staging` since the restore point is lost (sessions, logins). The V7 migration row disappears with it |

Never "undo" migrations by hand: deleting from `wallet_transactions` is rejected by its trigger, and
editing `flyway_schema_history` breaks later starts.

## After the rollout

- Update [docs/database.md → Neon](../docs/database.md#neon) (staging at V7, date) and the status
  in the [README](../README.md) (documentation change, its own pull request). *Done in W4-01
  (2026-11-15).*
- Delete `staging-before-week-03` once the rollout is accepted (**APPROVAL**). *Not done: the branch
  is kept by decision of the project owner.*
- Registration stays closed until it is opened on purpose
  ([render/README.md](render/README.md#registrations-on-a-public-url)).
