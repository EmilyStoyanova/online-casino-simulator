# Week 4 rollout: European roulette on staging (V8, V9)

This runbook describes how a **future, separately approved** deployment brings the Week 4 roulette
backend (Flyway **V8** and **V9**, `POST /api/v1/games/roulette/spins`) to the staging environment:

```
Vercel (frontend, auto-deploys main) ──/api──▶ Render staging backend ──▶ Neon branch staging (V7 before, V9 after)
```

**Status: prepared in W4-09, not executed.** No step of this runbook has been run. Neon `staging` is
at **V7**; V8 and V9 have been applied only to local and Testcontainers databases, and
`verify-roulette.sql` has never been run against Neon. Render Auto-Deploy is Off. The rollout is
decision **Q15** of [`docs/plans/week-04.md`](../docs/plans/week-04.md#131-decisions) (§13.1), which stays
*Proposed* until the project owner approves the deployment explicitly. The procedure follows the
executed [Week 3 wallet rollout](wallet-rollout.md) (V5 → V7, 2026-11-15).

Neon `production` is never touched: every command here uses the `staging` branch only. The existing
restore point `staging-before-week-03` is never used, changed or deleted by this runbook.

**Approval.** Every step marked **APPROVAL REQUIRED** changes a shared system (GitHub `main`, Render,
Neon) or data on staging, and needs an explicit, separate go-ahead from the project owner at the time
it is done. An approval of one step does not cover the next. Steps marked *read-only* change nothing.

## Contents

1. [What happens, in short](#1-what-happens-in-short)
2. [Preconditions and approvals](#2-preconditions-and-approvals)
3. [Read-only preflight at V7](#3-read-only-preflight-at-v7)
4. [Restore point `staging-before-week-04`](#4-restore-point-staging-before-week-04)
5. [Deployment](#5-deployment)
6. [Flyway V8 and V9 at start-up](#6-flyway-v8-and-v9-at-start-up)
7. [Read-only post-deployment verification](#7-read-only-post-deployment-verification)
8. [Functional spin test (separate approval)](#8-functional-spin-test-separate-approval)
9. [Success criteria](#9-success-criteria)
10. [Failure handling and stop conditions](#10-failure-handling-and-stop-conditions)
11. [Rollback and recovery](#11-rollback-and-recovery)
12. [After the rollout](#12-after-the-rollout)

## 1. What happens, in short

1. The approved Week 4 pull request is merged into `main` (**APPROVAL REQUIRED**, green CI; the pull
   request itself is task W4-10).
2. Vercel deploys `main` automatically. Week 4 changes no frontend file (re-checked in W4-10), so that
   deploy changes nothing visible.
3. Read-only checks confirm that `staging` is still at V7 with consistent wallets.
4. A Neon restore point `staging-before-week-04` is created (**APPROVAL REQUIRED**).
5. A manual Render deploy of `main` (**APPROVAL REQUIRED**; Auto-Deploy stays Off) starts the new
   backend. At start-up Flyway applies **V8** (two ledger `CHECK` constraints replaced) and then **V9**
   (`roulette_rounds`, `roulette_bets`, append-only triggers), each in its own transaction, before the
   application accepts requests. Neither migration writes a row.
6. Read-only checks confirm V9, consistent wallets and an empty, consistent roulette schema.
7. Optionally, one functional spin with a few virtual coins (**APPROVAL REQUIRED**, separately).

**Database changes are forward-only.** Rolling the application back on Render does **not** undo V8 or
V9. Once the first round has been played, the Week 3 build can no longer read the ledger of that
player; see [Rollback and recovery](#11-rollback-and-recovery).

## 2. Preconditions and approvals

All preconditions must hold before step 1 of the preflight. Checks in Render and Neon Console are
**view only**: nothing is edited there to satisfy a precondition. If one does not hold, stop.

| # | Check | How |
|---|---|---|
| P1 | The Week 4 review is complete; the pull request `feature/week-04-roulette` → `main` is approved and merged with green CI (step 5) | GitHub |
| P2 | CI on the merge commit is green: backend `verify` (PostgreSQL 17/18 migration tests, ArchUnit, JaCoCo for `wallet.service`, `game.model`, `game.service`) and the frontend checks | GitHub Actions |
| P3 | The merged `main` contains exactly V1–V9 and V1–V7 are byte-for-byte those already applied on staging (Flyway validates their checksums at start-up) | `git diff --name-status b07e656 <merge commit> -- backend/src/main/resources/db/migration` prints exactly two lines, `A …/V8__add_roulette_wallet_transaction_types.sql` and `A …/V9__create_roulette_rounds.sql` |
| P4 | `.env.neon` points to the **staging** branch's direct endpoint (no `-pooler`) | preflight step 1 |
| P5 | Render: `APP_REGISTRATION_ENABLED` is `false` (or unset with profile `render`) and stays so for the whole rollout | Render → service → Environment (view only) |
| P6 | Render: Auto-Deploy **Off**, branch `main`, `SPRING_PROFILES_ACTIVE=neon,render` | Render → service → Settings / Environment (view only) |
| P7 | Render: `APP_WALLET_STARTING_BALANCE` and `APP_WALLET_LOCK_TIMEOUT` are **not** set (defaults 1000 and 5 s). Week 4 needs **no new environment variable or secret** | Render → Environment (view only) |
| P8 | Neon: the project has room for one more branch without deleting anything; `staging-before-week-03` is present and stays | Neon Console → Branches (view only) |
| P9 | A quiet time window; nobody else deploys or uses staging during it | team |
| P10 | The project owner has read this runbook and the rollback limits of [§11](#11-rollback-and-recovery) | review |

**Approvals needed, each separately and at the time of the step:**

| Id | Step | Changes |
|---|---|---|
| A1 | [Restore point](#4-restore-point-staging-before-week-04) | Creates a Neon branch |
| A2 | [Merge](#step-5-merge-approval-required) | GitHub `main`; Vercel redeploys `main` |
| A3 | [Manual Render deploy](#step-6-manual-render-deploy-approval-required) | Render; Flyway applies V8 and V9 to Neon `staging` |
| A4 | [Sign-in for the functional test](#81-sign-in-approval-required) | Writes a session row and `last_login_at` |
| A5 | [Functional spin](#82-one-spin-and-its-replay-approval-required) | Debits and possibly credits the player's virtual coins; writes one round, one bet and one or two ledger entries |
| A6 | Any action of [§10](#10-failure-handling-and-stop-conditions) or [§11](#11-rollback-and-recovery) (suspend, rollback, restore, redeploy) | Render and/or Neon |
| A7 | Deleting `staging-before-week-04` later (not part of this rollout) | Neon |

The order matters: A2 may happen earlier (at the end of W4-10), but A1 is done **after** the
preflight and **immediately before** A3, so the restore point holds the last V7 state.

Why registration stays closed: Week 3 code (the old instance, still serving during the deploy) and
Week 4 code both open a wallet at registration, so a rolling deploy is not expected to create a player
without a wallet this time (provided the old instance behaves on the new schema as expected; untested,
[§6](#6-flyway-v8-and-v9-at-start-up)). Registration nevertheless stays closed as it is today: the
rollout changes no environment variable, closed registration removes that untested path entirely, and
a new account would only add data that a restore would lose.

## 3. Read-only preflight at V7

All commands from the repository root, in Git Bash, with Docker running.
[`readonly-query.sh`](neon/readonly-query.sh) runs everything in a `READ ONLY` transaction that is
rolled back; credentials come from `.env.neon` and are never printed.

**Do not run `verify-roulette.sql` in the preflight.** It is written for V9 only: at V7 its tables do
not exist. It is run only after step 8 reports V9.

### Step 1. Location and schema state (read-only)

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-migrations.sql
```

Expected:
- `\conninfo`: the staging endpoint host (no `-pooler`), database `neondb`, an SSL connection;
- `neon_branch_id` equals the **staging** branch ID in Neon Console → Branches → `staging` (**stop**
  if it is `production`, `staging-before-week-03` or anything else);
- every row of the migration history has `success = t`, versions 1–7;
- `schema_state` = `V7: Week 3 wallet schema`, `failed_migrations` = 0;
- every expected table `present = t` (the roulette tables are not listed below V9),
  `unexpected_tables` = 0;
- the counts line: `interests` = 8; `wallets` = `players`; `ledger_entries` ≥ `players`;
  `roulette_rounds` and `roulette_bets` empty (`NULL`: the tables do not exist yet).

### Step 2. Existing wallets and ledger (read-only)

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-wallets.sql
```

Expected: every column of the first result `0` (no player without a wallet, no wallet of a
non-player, every wallet with exactly one starting entry, every balance equal to its ledger); second
result `players` = `wallets` = `starting_entries`. The last verified state (2026-11-15) was one player
with 1,000 coins and one `STARTING_BALANCE` entry; the current values may differ and are what step 3
records.

### Step 3. Record the baseline

Write down, from steps 1 and 2 (counts only, no row contents):

| Value | Source | Expected after the deploy |
|---|---|---|
| `players`, `disabled_players`, `admins` | step 1, last line | unchanged |
| `wallets` | step 1 | unchanged (= `players`) |
| `ledger_entries` | step 1 | unchanged: V8 and V9 write no row |
| `sessions`, `throttle_rows` | step 1 | may change (expiry, sign-ins) |
| time (UTC) of the preflight | clock | — |

### Step 4. Stop conditions

Do not continue if anything differs: a wrong branch, a failed or unexpected migration, a schema
version other than 7, unexpected tables, any wallet violation, or `wallets` ≠ `players`.
Investigate first; nothing has changed yet.

## 4. Restore point `staging-before-week-04`

**APPROVAL REQUIRED (A1)** — it creates a resource in the Neon project.

Neon restores by branching, not by dump files. Directly before the deploy:

1. Neon Console → project `online-casino-simulator` → **Branches** → `staging` → **Create branch**
   (child branch), name **`staging-before-week-04`**, from the **current point in time**. This is
   instant (copy-on-write) and does not touch `staging` itself.
2. Check in the Console (view only) that its parent is `staging` and its creation time is after the
   preflight. Note the creation time (UTC); Neon's point-in-time restore can also use it within the
   project's history retention window (short on the Free plan; the branch does not depend on it).
3. Do not connect any application, script or `.env.neon` to the new branch; it only preserves the V7
   state. `readonly-query.sh` keeps pointing at `staging`.
4. Leave `staging-before-week-03` (the V5 state) untouched. Deleting either restore point needs its
   own approval later (A7).

If the branch cannot be created (for example the branch limit is reached), **stop**: do not delete
another branch to make room without a separate decision, and do not deploy without a restore point.

## 5. Deployment

### Step 5. Merge (APPROVAL REQUIRED)

**A2.** Merge the approved Week 4 pull request into `main` (it may already have happened at the end of
W4-10). Vercel then deploys `main` automatically; the frontend bundle has no Week 4 change. Render does
**not** deploy, because Auto-Deploy is Off.

### Step 6. Manual Render deploy (APPROVAL REQUIRED)

**A3.** Render → service → **Manual Deploy → Deploy latest commit** (the merge commit). Nothing else
is changed in Render: no environment variable, no setting. This is the step that migrates Neon
`staging`.

### Step 7. Watch the log

The build takes a few minutes, the start on the Free instance about 2 more. Expected, in this order:

```
The following 2 profiles are active: "neon", "render"
Registration is closed (APP_REGISTRATION_ENABLED=false): POST /api/v1/auth/register answers 403
Current version of schema "public": 7
Migrating schema "public" to version "8 - add roulette wallet transaction types"
Migrating schema "public" to version "9 - create roulette rounds"
Successfully applied 2 migrations to schema "public", now at version v9 (execution time …)
Started CasinoSimulatorApplication
```

(Message formats as in the Week 3 rollout, Flyway 12.4; timestamps and timings differ.)

**Stop the service** (Render → **Suspend**, **APPROVAL REQUIRED**, A6) if the log shows
`Current version of schema "public": << Empty Schema >>`, a current version other than 7, or
migrations other than V8 and V9 being applied: it is pointing at a different database. If the start
fails, Render keeps the old instance serving; continue with [§10](#10-failure-handling-and-stop-conditions).

## 6. Flyway V8 and V9 at start-up

| | V8 `add roulette wallet transaction types` | V9 `create roulette rounds` |
|---|---|---|
| Owner | `wallet` (W4-03) | `game` (W4-05) |
| Change | One `ALTER TABLE wallet_transactions`: replaces `ck_wallet_transactions_type` (`STARTING_BALANCE`, `ROULETTE_STAKE`, `ROULETTE_PAYOUT`) and `ck_wallet_transactions_direction` (stake < 0, payout and starting balance > 0); names unchanged | New function `reject_row_change()`, tables `roulette_rounds` and `roulette_bets` with their constraints and foreign keys, two append-only triggers |
| Rows written | none; existing rows are validated against the new checks | none; both tables start empty |
| Locks | Short `ACCESS EXCLUSIVE` on `wallet_transactions` while the existing rows are validated (a few rows on staging: milliseconds); the old instance's ledger reads wait briefly | Only on the new objects, plus the short locks of the foreign keys on `users` and `wallet_transactions` |
| If it fails | Rolled back completely; schema stays **V7**, V8 not recorded | Rolled back completely; schema stays **V8**, V9 not recorded |
| Week 3 build on the new schema | Expected to work (**not tested**): it never writes the new types | Expected to work (**not tested**): it does not map the new tables |

How Flyway behaves here:
- It runs once, in the new instance, before the web server accepts requests. The Free plan has no
  pre-deploy command; migration at start-up is the configured mechanism.
- It validates the checksums of V1–V7 first; a changed file stops the start (P3).
- V8 and V9 run in **separate transactions** (PostgreSQL has transactional DDL). Nothing is
  half-applied within a migration. A failed migration is not recorded, so the next start retries it.
- If the new instance does not become healthy, Render keeps the old instance serving.
- Flyway's advisory lock prevents two instances from migrating at the same time (direct endpoint,
  not the pooler; `readonly-query.sh` refuses a `-pooler` host).
- Both migrations are tested on PostgreSQL 17.11 and 18.6 (Neon runs 18) from a V7 schema with
  players and wallets, including a failed V8 and a failed V9 that leave V7/V8 and apply on the next
  start (`MigrationCompatibilityIT`). They have **not** run against Neon.

**Week 3 compatibility is an expectation, not a verified fact.** What the tests prove: V8 and V9
leave every existing wallet, ledger row, constraint name and trigger unchanged, and the wallet code
paths of the **Week 4 build** keep working on a V9 schema (AC-W4-05.5). What has **not** been tested:
the actual Week 3 build (`main` at `b07e656`) starting and serving on a V8 or V9 schema — neither
locally nor on staging. The expectation rests on schema analysis: V8 only widens two `CHECK`
constraints, V9 only adds objects, Hibernate's `validate` checks only the mapped tables and columns,
and Flyway's default `ignoreMigrationPatterns = *:future` accepts applied migrations that the older
build does not know. This matters in two places: the old instance that keeps serving during the
deploy (step 7) and an application rollback ([§11](#11-rollback-and-recovery) row 4). If the old build
does not behave as expected, the database restore of row 6 is the fallback.

## 7. Read-only post-deployment verification

Run in this order. Step 10 depends on step 8.

### Step 8. Schema state V9 (read-only)

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-migrations.sql
```

Expected:
- the same `neon_branch_id` as in step 1 (staging);
- versions 1–9, all `success = t`; `schema_state` = **`V9: Week 4 roulette rounds`**;
  `failed_migrations` = 0;
- `roulette_rounds` and `roulette_bets` listed with `present = t` (since version 9), together with
  every earlier table; `unexpected_tables` = 0;
- counts: `players`, `disabled_players`, `admins`, `wallets` and `ledger_entries` as recorded in
  step 3; `roulette_rounds` = 0 and `roulette_bets` = 0.

**Gate:** only if `schema_state` is `V9: Week 4 roulette rounds` continue with step 10. At V7 or V8,
do **not** run `verify-roulette.sql`; go to [§10](#10-failure-handling-and-stop-conditions).

### Step 9. Wallets and ledger unchanged (read-only)

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-wallets.sql
```

Expected: exactly the result of step 2 (every violation counter `0`; `players` = `wallets` =
`starting_entries`). V8 changed two constraint definitions only.

### Step 10. Round–ledger reconciliation (read-only, V9 only)

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-roulette.sql
```

Expected right after the deploy:
- first result: all eleven counters `0` (`rounds_without_1_to_10_bets` …
  `roulette_entries_not_in_exactly_one_round`); the script is valid on empty tables;
- second result: `rounds`, `bets`, `players_with_rounds`, `winning_rounds`, `stake_entries`,
  `payout_entries` all `0` (no round has been played, and no roulette ledger entry exists).

The meaning of each counter is in the script's header and in
[docs/database.md → Roulette rounds and bets](../docs/database.md#roulette-rounds-and-bets-v9).

### Step 11. API smoke checks: authentication and CSRF (read-only for data)

`B=https://online-casino-simulator.onrender.com`; the Vercel origin
`https://online-casino-simulator.vercel.app` gives the same answers through its rewrite. None of these
requests can write: they stop at the CSRF filter or the authorization rule.

```bash
B=https://online-casino-simulator.onrender.com
curl -s --max-time 300 $B/actuator/health/readiness            # {"status":"UP"}
curl -s $B/api/v1/system/status                                # "schemaVersion":"9"
curl -s -o /dev/null -w '%{http_code}\n' $B/api/v1/wallet                     # 401
curl -s -o /dev/null -w '%{http_code}\n' $B/api/v1/wallet/transactions        # 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
  -d '{"bets":[]}' $B/api/v1/games/roulette/spins                             # 403 (CSRF before anything else)
```

With a valid CSRF token from a temporary cookie jar but **no session**, the spin is rejected by the
`PLAYER` rule before the controller (no body is read, nothing is written):

```bash
J=$(mktemp)
curl -s -c "$J" -o /dev/null $B/api/v1/auth/csrf
curl -s -b "$J" -H "X-XSRF-TOKEN: $(awk '$6=="XSRF-TOKEN"{print $7}' "$J")" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b' \
  -d '{"bets":[{"category":"RED_BLACK","color":"RED","amount":1}]}' \
  $B/api/v1/games/roulette/spins; echo
rm -f "$J"
```

Expected: `401` with `"code":"AUTHENTICATION_REQUIRED"`. Then repeat the security regression checks
and the closed-registration probe of
[render/README.md → Verify after a deploy](render/README.md#verify-after-a-deploy) (HSTS and cookie
flags, CORS `403`, Swagger `404`, actuator `401`, `403 REGISTRATION_DISABLED`).

## 8. Functional spin test (separate approval)

The read-only checks of §7 prove the schema and the access rules, not a played round. The integration
tests cover the spin completely (`RouletteSpinIT`, `RouletteConcurrencyIT`, `RoundLedgerIntegrityIT`),
so skipping this section is acceptable. If it is done, it uses **virtual coins only**, the **existing**
staging player, **one** small bet and nothing else.

**Point of no return.** After this spin a roulette ledger entry exists. From then on an application
rollback to the Week 3 build is no longer safe without a database restore
([§11](#11-rollback-and-recovery)). Decide A5 with that in mind.

There is no roulette screen before Week 5, so the request is sent from the browser's DevTools console
on the Vercel origin. The session cookie is `HttpOnly`: the console cannot read it, and it is never
copied into a command, ticket or chat. Only the readable `XSRF-TOKEN` cookie is used, exactly as the
SPA does.

### 8.1 Sign-in (APPROVAL REQUIRED)

**A4.** Sign in as the existing staging **player** through `https://online-casino-simulator.vercel.app`
(this writes a session row and `last_login_at`). Warm the backend first (step 11's readiness check),
because a Render cold start can exceed Vercel's 120 s proxy timeout. In the same browser open
`/api/v1/wallet` and write down `balance` as **B₀** (read-only), and look at
`/api/v1/wallet/transactions` (newest first; expected: no `ROULETTE_*` entry yet).

### 8.2 One spin and its replay (APPROVAL REQUIRED)

**A5.** In DevTools → Console on the Vercel page:

```js
await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
const xsrf = decodeURIComponent(document.cookie.split('; ')
  .find(c => c.startsWith('XSRF-TOKEN=')).slice('XSRF-TOKEN='.length));
const key = crypto.randomUUID();           // one spin attempt = one key
const spin = () => fetch('/api/v1/games/roulette/spins', {
  method: 'POST', credentials: 'same-origin',
  headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf, 'Idempotency-Key': key },
  body: JSON.stringify({ bets: [{ category: 'RED_BLACK', color: 'RED', amount: 10 }] }),
}).then(async r => ({ status: r.status, body: await r.json() }));
const first = await spin(); first
```

Expected `status` `200` and a `RouletteSpinResult`:
- `winningNumber` 0–36 and `winningColor` its color (`GREEN` only for 0);
- `bets` = the one bet with `payout` **20** if `winningColor` is `RED`, otherwise **0**;
- `totalStake` 10, `totalPayout` = that payout, `balance` = B₀ − 10 + `totalPayout`.

Then the replay with the **same key** (no second debit, no draw, no write):

```js
const second = await spin();
second.status === 200 && JSON.stringify(second.body) === JSON.stringify(first.body)   // true
```

If the first call ended with a proxy `502`/`504` or a network error, do **not** create a new key: run
`await spin()` again. Either the round was committed and its original result comes back, or nothing was
recorded and the round is played once. A `409 INSUFFICIENT_FUNDS` means B₀ < 10: stop, do not add coins.

### 8.3 Wallet and history (read-only)

In the same browser:
- `/api/v1/wallet`: `balance` = `first.body.balance`;
- `/api/v1/wallet/transactions`: a loss adds one `ROULETTE_STAKE` entry (`amount` −10,
  `balanceAfter` B₀ − 10) on top; a win adds that entry and above it one `ROULETTE_PAYOUT` entry
  (`amount` +20, `balanceAfter` B₀ + 10). Entries never contain keys, references or ids of other
  users.

An existing **administrator** (if step 3 counted one) would get `403 ACCESS_DENIED` for the spin; that
check needs its own sign-in approval and is optional (covered by `RouletteSpinIT`).

### 8.4 Reconciliation after the spin (read-only)

```bash
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-wallets.sql
bash infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-roulette.sql
```

Expected: `verify-wallets.sql` all violation counters `0`; `verify-roulette.sql` first result all `0`,
second result `rounds` 1, `bets` 1, `players_with_rounds` 1, `winning_rounds` 1 for a win or 0 for a
loss, `stake_entries` 1, `payout_entries` = `winning_rounds`. Sign out afterwards.

## 9. Success criteria

All of them:
- the log shows exactly V8 and V9 applied (`now at version v9`) and the application started;
- steps 8–11 match (V9 state, unchanged wallets and ledger, every roulette counter 0,
  `schemaVersion` 9, `401`/`403` as listed, security regression checks unchanged);
- §8, if approved, matches, including the identical replay and the reconciliation;
- no `5xx` in the Render log during the first minutes, apart from the documented cold start;
- registration still closed; Neon `production` and `staging-before-week-03` untouched.

## 10. Failure handling and stop conditions

**Stop** (do not continue with the next step) when any of these occurs. Record what was seen as
counts and status codes only, never row contents, cookies or connection values. Every remedial action
is **APPROVAL REQUIRED** (A6) and decided with the project owner.

| Observation | Meaning | First action |
|---|---|---|
| Preflight differs from §3 (branch, version, tables, wallet violations) | Wrong target or unexpected state | Stop before anything changed; investigate |
| Restore point cannot be created | No safety net | Stop; do not deploy |
| Render build fails | Nothing changed (schema V7) | Fix in a new commit and pull request; deploy later |
| Log: empty schema, current version ≠ 7, or other migrations | Wrong database | **Suspend** the service (A6); compare `.env`/Render values with the staging branch without printing them |
| Log: V8 fails | Schema stays V7; old instance serves | Read the error; fix in a new commit; [§11](#11-rollback-and-recovery) row 2 |
| Log: V8 applied, V9 fails | Schema V8; old instance serves Week 3 code (expected to be compatible with V8, not tested; watch its log and the read-only checks) | [§11](#11-rollback-and-recovery) row 3 |
| Started, but step 8 is not `V9: Week 4 roulette rounds` | Inconsistent state | Do not run `verify-roulette.sql`; investigate |
| Step 9 or 10 reports a violation | Data inconsistent | No spin; consider **Suspend** (A6); [§11](#11-rollback-and-recovery) row 6 |
| Step 11: `schemaVersion` ≠ 9, a spin answers anything but `403`/`401`, or a security check differs | Wrong build or configuration | Stop; application rollback if no round exists ([§11](#11-rollback-and-recovery) row 4) |
| §8: unexpected status, a replay that differs, or a balance that does not add up | Functional defect | No further spins; reconciliation of 8.4; roll forward ([§11](#11-rollback-and-recovery) row 5) |

## 11. Rollback and recovery

Flyway has no undo, and the ledger, `roulette_rounds` and `roulette_bets` are append-only. Every
option below is **APPROVAL REQUIRED** (A6).

| # | Situation | Action | Effect |
|---|---|---|---|
| 1 | Before step 6 (nothing deployed) | Nothing to undo. The restore point may stay | No change on staging |
| 2 | V8 failed: schema at V7 | Nothing to undo in the database. Fix the cause in a new commit (V8 itself is not edited: it is applied in local and test databases); deploy again later | Old instance keeps serving |
| 3 | V8 applied, V9 failed: schema at V8 | Nothing to undo; Week 3 code is expected to work on V8 (not tested, [§6](#6-flyway-v8-and-v9-at-start-up)). Fix the cause in a new commit; the next start applies V9 only | Old instance keeps serving; no roulette tables |
| 4 | V8 and V9 applied, the new application misbehaves, **no round played** (step 10 / 8.4: `rounds` 0, `stake_entries` 0) | **Application rollback (conditional, unverified):** Render → Deploys → previous deploy → *Rollback*. The schema stays at V9. It is **expected** to work because the Week 3 build never writes the new types and does not map the new tables, and its Flyway (V1–V7 only) should accept the applied V8/V9 as future migrations by default (`ignoreMigrationPatterns = *:future`). **This rollback has not been tested** with the Week 3 build, locally or on staging. If the old build does not start, Render keeps the current instance; then fall back to row 6 | Spin endpoint gone, schema and data kept. Re-deploying the fixed build later needs no migration |
| 5 | **Rounds exist** (at least one `ROULETTE_*` entry) and the application misbehaves | **Roll forward** with a fix (new commit, pull request, approved deploy). Do **not** roll back to the Week 3 build alone: its ledger enum has no `ROULETTE_STAKE`/`ROULETTE_PAYOUT`, so reading those rows is expected to fail and `GET /api/v1/wallet/transactions` of every player who played would answer `500` (risk R9; from code analysis, not tested) | Data kept; roulette rounds stay consistent with the ledger |
| 6 | The data itself is wrong (a counter of step 9/10/8.4 is not 0), or a return to Week 3 code is required after rounds exist | **Database restore:** point the service at a branch restored from `staging-before-week-04` (or restore `staging` to the noted time in Neon Console), **then** roll the application back as in row 4 | Everything written to `staging` since the restore point is lost: rounds, bets, roulette ledger entries and balance changes, sessions, logins. The V8/V9 rows of `flyway_schema_history` disappear with it, so the next Week 4 deploy applies V8 and V9 again |

Never, in any situation:
- delete or update rows of `wallet_transactions`, `roulette_rounds` or `roulette_bets` (the triggers
  reject it, and it would break the reconciliation), disable a trigger, or insert compensating rows by
  hand (corrections are a separate, reviewed decision);
- edit `flyway_schema_history`, run Flyway `clean`/`repair`, or enable `baseline-on-migrate` against
  staging;
- connect the application or `readonly-query.sh` to a restore point except as part of an approved
  restore;
- touch Neon `production` or `staging-before-week-03`.

## 12. After the rollout

Documentation only, in its own pull request:
- Add a **Rollout record** at the top of this file (date, merge commit, restore point, log result,
  steps 8–11 and §8 results as counts and status codes), as in the
  [Week 3 record](wallet-rollout.md#rollout-record-2026-11-15).
- Update [docs/database.md → Neon](../docs/database.md#neon) and "Where each version is applied"
  (staging at V9, date), the status in the [README](../README.md),
  [docs/architecture.md → Deployment view](../docs/architecture.md#6-deployment-view-staging-today-final-decision-week-12),
  [infrastructure/README.md](README.md), [render/README.md](render/README.md), and Q15 in
  [docs/plans/week-04.md](../docs/plans/week-04.md) §13.1.
- Keep `staging-before-week-04` until the rollout is accepted; deleting it is A7. Registration stays
  closed until it is opened on purpose
  ([render/README.md](render/README.md#registrations-on-a-public-url)).
