# Infrastructure

| Location | Contents | Stage |
|---|---|---|
| `neon/` | Read-only checks against a Neon branch, run with `readonly-query.sh` (psql in Docker, verified TLS, credentials from `.env.neon`, everything in a rolled-back `READ ONLY` transaction): `preflight.sql` before the first migration of an empty branch; `verify-migrations.sql` for the schema state (recognises V5, V7, V8 and V9); `verify-wallets.sql` for the wallet invariants after V6/V7 ([docs/database.md](../docs/database.md#neon)); `verify-roulette.sql` for the round–ledger reconciliation at V9 (Week 4, [docs/database.md](../docs/database.md#roulette-rounds-and-bets-v9); not run against Neon yet). The three `verify-*` scripts are tested on PostgreSQL 17 and 18 (`MigrationCompatibilityIT`) | Deployment foundation, Weeks 3–4 |
| `render/` | Runbook for the backend staging service on Render: settings, environment variables, browser steps, verification, Free plan limits, public security configuration ([render/README.md](render/README.md)) | Deployment foundation |
| [`../backend/Dockerfile`](../backend/Dockerfile) | Backend image: multi-stage Maven build, Temurin 21 JRE (Alpine), non-root, pinned base images; build context `backend/` | Deployment foundation |
| `vercel/` | Runbook for the frontend staging project on Vercel: dashboard settings, the same-origin `/api` rewrite (`frontend/vercel.json`), the required backend CORS origin, cookie and header semantics, cold starts versus the proxy timeout, verification ([vercel/README.md](vercel/README.md)) | Deployment foundation |
| [`wallet-rollout.md`](wallet-rollout.md) | Runbook for the Week 3 wallet on staging (Flyway V6/V7 at Render start-up): preconditions, read-only preflight, Neon restore point, deploy, read-only checks, success/failure criteria, rollback. **Executed on 2026-11-15** with approval (rollout record at its top); kept as the template for later migrations | Week 3 |
| final hosting decision | Production routing and hosting (ADR-0007) | 12 |

**Current staging state:** Render (`https://online-casino-simulator.onrender.com`) and Vercel
(`https://online-casino-simulator.vercel.app`) run `main` (Weeks 1–3); the Neon `staging` branch is
at schema **V7** since the Week 3 rollout of 2026-11-15, and its V5 restore point
`staging-before-week-03` is kept. Registration stays closed. Neon `production` is untouched.

Local development infrastructure lives in the repository-root `docker-compose.yml` (PostgreSQL).
