# Infrastructure

| Location | Contents | Stage |
|---|---|---|
| `neon/` | Read-only checks against the Neon database: `readonly-query.sh` (psql in Docker, verified TLS, credentials from `.env.neon`), `preflight.sql` before and `verify-migrations.sql` after the first migration ([docs/database.md](../docs/database.md#neon)) | Deployment foundation |
| `render/` | Runbook for the backend staging service on Render: settings, environment variables, browser steps, verification, Free plan limits, public security configuration ([render/README.md](render/README.md)) | Deployment foundation |
| [`../backend/Dockerfile`](../backend/Dockerfile) | Backend image: multi-stage Maven build, Temurin 21 JRE (Alpine), non-root, pinned base images; build context `backend/` | Deployment foundation |
| `vercel/` | Runbook for the frontend staging project on Vercel: dashboard settings, the same-origin `/api` rewrite (`frontend/vercel.json`), the required backend CORS origin, cookie and header semantics, cold starts versus the proxy timeout, verification ([vercel/README.md](vercel/README.md)) | Deployment foundation |
| final hosting decision | Production routing and hosting (ADR-0007) | 12 |

Local development infrastructure lives in the repository-root `docker-compose.yml` (PostgreSQL).
