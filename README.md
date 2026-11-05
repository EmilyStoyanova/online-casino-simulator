# Online Casino Simulator

A full-stack web application that recreates authentic casino rules and win/loss probabilities —
starting with **European roulette** — using **virtual currency only**. The goal is education:
players see how the odds really work, learn from short modules and quizzes, and use
responsible-play tools.

> **No real money, ever.** There are no deposits, withdrawals, purchases of virtual currency or
> cash-equivalent prizes. Virtual coins have no monetary value.
>
> **Not a treatment.** The platform is a simulator and educational tool. It is not presented as a
> clinically validated treatment for gambling addiction.

**Audience:** adults (18+) who want to understand casino probabilities without risk, and the
developers and reviewers of this project. It is built as part of the **SAP mentorship program**
following a 12-week implementation plan
([`docs/OnlineCasinoSimulator_ImplementationPlan_v1.1.pdf`](docs/OnlineCasinoSimulator_ImplementationPlan_v1.1.pdf), version 1.1;
the original version 1.0 is [`docs/OnlineCasinoSimulator_ImplementationPlan.pdf`](docs/OnlineCasinoSimulator_ImplementationPlan.pdf)).

---

## Status

| Stage | Weeks | Scope | Status |
|---|---|---|---|
| 1 — Foundation | 1 | Repository, tooling, database, CI, documentation | ✅ Done |
| | 2 | Authentication, users, roles, profile & interests | 🔍 Implemented, in review (branch `feature/week-02-auth`) |
| 2 — Virtual currency & game logic | 3–4 | Wallet ledger, roulette engine | Planned |
| 3 — Casino UI | 5–6 | Roulette UI, Phaser animations | Planned |
| 4 — Learning & rewards | 7–8 | Learning library, quizzes, rewards | Planned |
| 5 — Statistics & responsible play | 9–10 | Dashboard, limits, admin panel | Planned |
| 6 — QA & release | 11–12 | Testing, deployment, beta release | Planned |

### What works today

- **Accounts:** registration with 18+ confirmation and automatic sign-in, login, logout. Passwords
  are hashed with BCrypt and must be 12 characters to 72 UTF-8 bytes long, different from the email
  and the display name, and not on a list of common passwords.
- **Sessions:** server-side sessions in PostgreSQL (Spring Session JDBC) with an `HttpOnly` cookie.
  They end after 30 minutes of inactivity or 12 hours after sign-in. CSRF protection applies to
  every state-changing request.
- **Abuse protection:** throttling of failed logins and of registrations (`429` with
  `Retry-After`).
- **Roles:** `PLAYER` for everyone who registers. `ADMIN` only for an administrator created once
  from the environment. `/api/v1/admin/**` is reserved for administrators, but there are no admin
  endpoints yet.
- **Profile:** a protected `/profile` page to change the display name and choose up to 8
  educational interests.
- **Platform status:** a page and an endpoint showing backend, database and schema version.

### Not implemented yet

Virtual wallet and balances, roulette and every other game, learning modules, quizzes, rewards,
statistics, play limits, the admin panel and deployment are **planned** (see the table above).
Nothing in the current application handles coins or game rounds.

## Architecture at a glance

```
 Browser ──► React + TypeScript (Vite) ──/api/v1──► Spring Boot modular monolith ──► PostgreSQL
             renders server results only            common · auth · user            Flyway-managed
                                                    (+ wallet · game · learning ·   schema
                                                     responsibleplay · admin later)
```

- The backend is one deployable with explicit module boundaries, checked by ArchUnit.
- The API is **design-first**: [`openapi.yaml`](backend/src/main/resources/static/openapi.yaml) is
  the source of truth, and contract tests keep the code in sync with it.
- Authentication uses session cookies and CSRF tokens, with no tokens in browser storage.
- From Week 3 the backend will be **authoritative** for every game outcome, payout and balance; the
  frontend only renders server-confirmed results.

Details: [`docs/architecture.md`](docs/architecture.md).

## Tech stack

| Area | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Web MVC, Spring Security, Spring Session JDBC, Spring Data JPA (Hibernate 7), Bean Validation |
| Database | PostgreSQL 17, Flyway migrations |
| API | Design-first OpenAPI 3.1 contract, springdoc-openapi / Swagger UI |
| Frontend | React 19, TypeScript 6, Vite 8, React Router 8, Tailwind CSS 4 |
| Testing | JUnit Jupiter, Mockito, Spring Security Test, Testcontainers, ArchUnit, Vitest, React Testing Library |
| Tooling | Maven Wrapper (Maven 3.9), Docker Compose, GitHub Actions |
| Later stages | Phaser (Week 6), Playwright (Week 11), Cloudflare Pages / Render / Neon (Week 12) |

Exact versions and the reasons for them: [ADR-0002](docs/decisions/0002-technology-baseline.md).

## Repository layout

```
├── backend/                 Spring Boot application (Maven Wrapper: ./mvnw)
│   └── src/main/java/com/casino/<module>/   one package per module (common, auth, user)
├── frontend/                React + Vite single-page application
├── infrastructure/          Docker and deployment assets (Weeks 11–12; only a README so far)
├── docs/                    Architecture, API, database, plans, ADRs
├── .github/workflows/       CI pipeline
├── docker-compose.yml       Local PostgreSQL
└── .env.example             Template for the local configuration
```

---

## Getting started (Windows 11 + Git Bash)

The primary development environment is **Windows 11 with Git Bash**. All commands below are Git
Bash commands. On macOS and Linux they are the same, except for the `JAVA_HOME` path.

### Prerequisites

| Tool | Version | Check (Git Bash) |
|---|---|---|
| Git for Windows (includes Git Bash and `openssl`) | current | `git --version`, `openssl version` |
| JDK | 21 (e.g. Eclipse Temurin) | `"$JAVA_HOME/bin/java" -version` |
| Node.js + npm | Node ≥ 22.12; 24 LTS recommended ([`frontend/.nvmrc`](frontend/.nvmrc)) | `node --version`, `npm --version` |
| Docker Desktop (Docker Compose v2) | current | `docker info --format '{{.ServerVersion}}'`, `docker compose version` |

Maven does **not** need to be installed: the Maven Wrapper (`backend/mvnw`) downloads the pinned
version on first use.

### Startup order

| Step | Working directory | Command | Keeps running |
|---|---|---|---|
| 1. Configure once | repository root | `cp .env.example .env` + secret (below) | — |
| 2. Database | repository root | `docker compose up -d` | in the background |
| 3. Backend | `backend/` | `./mvnw spring-boot:run` | yes, terminal 1 |
| 4. Frontend | `frontend/` | `npm ci` (first time), then `npm run dev` | yes, terminal 2 |

Docker Desktop must be running before step 2. The frontend works without the backend, but every API
call then fails.

### 1. Java 21 for Git Bash

The Maven Wrapper needs `JAVA_HOME`. Without it, it stops with
`The JAVA_HOME environment variable is not defined correctly`. Add these two lines to `~/.bashrc`,
with the folder of **your** JDK 21 installation, then open a new Git Bash window:

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.x.x-hotspot"   # your JDK 21 folder
export PATH="$JAVA_HOME/bin:$PATH"
```

Check it from `backend/` with `./mvnw -v`. The output must show `Java version: 21…`.

### 2. Configure the environment

```bash
cp .env.example .env
```

Then edit `.env` in an editor:

- Change `POSTGRES_PASSWORD`, and set `DB_PASSWORD` to the same value.
- Set the **required** throttling secret. This command writes a random value into `.env` without
  showing it:

  ```bash
  sed -i "s|^APP_SECURITY_THROTTLE_SECRET=.*|APP_SECURITY_THROTTLE_SECRET=$(openssl rand -base64 32)|" .env
  grep -c '^APP_SECURITY_THROTTLE_SECRET=.' .env    # prints 1 when a value is set
  ```

How the configuration is loaded:

- `.env` is **git-ignored** (`.gitignore`); only `.env.example` with placeholders is committed.
- Docker Compose reads `.env` from the repository root.
- The backend imports it itself (`spring.config.import` in
  [`application.yml`](backend/src/main/resources/application.yml)), when started from `backend/` or
  from the repository root. **Do not `export` or `source` the file**: the backend does not need it,
  and exported values would leak into every program started from that shell.
- Real environment variables override `.env`. A forgotten `export DB_URL=…` in `~/.bashrc` wins
  over the file.
- Write values without quotes and without spaces around `=`. The backend reads the file as Java
  properties and would keep the quotes.

All variables are listed in the [configuration reference](#configuration-reference).

### 3. Start the database

```bash
docker compose up -d     # PostgreSQL 17 on 127.0.0.1:${POSTGRES_PORT} (default 5432)
docker compose ps        # wait until STATUS shows "(healthy)"
```

The data is kept in the Docker volume `postgres-data` across restarts. `docker compose down` stops
the container and keeps the data.

### 4. Start the backend (http://localhost:8080)

```bash
cd backend
./mvnw spring-boot:run
```

On start-up Flyway applies pending migrations; the log ends with `Started CasinoSimulatorApplication`.
Stop it with `Ctrl+C`. In PowerShell or `cmd.exe` the wrapper is `mvnw.cmd` instead of `./mvnw`.

### 5. Start the frontend (http://localhost:5173)

```bash
cd frontend
npm ci          # first time and after dependency changes: installs exactly package-lock.json
npm run dev
```

Open **http://localhost:5173** (not `127.0.0.1`: only `http://localhost:5173` is an allowed origin,
see [Troubleshooting](#troubleshooting)). The Vite dev server proxies `/api/*` to the backend, so the
browser talks to a single origin.

In the browser:

- The home page shows the **Platform status**.
- **Create account** (`/register`) and **Sign in** (`/login`) use the real backend.
- **My profile** (`/profile`, signed-in users only) edits the display name and the interests.
- After a page reload the session is restored from the cookie.

### 6. Optional: create the first administrator

Registration always creates a `PLAYER`. To get one `ADMIN` account, set **both** variables in `.env`
and start the backend once:

```properties
APP_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
APP_BOOTSTRAP_ADMIN_PASSWORD=<12+ characters, not a common password>
```

How the bootstrap behaves:

- The password must meet the registration policy. With only one of the two variables set, or with
  invalid values, the backend does not start, and the error names the rule but never the value.
- An existing account with that email is never changed or promoted.
- **Remove both lines from `.env` after the first start.** The password is then only stored as a
  BCrypt hash.

### Useful URLs

| URL | Purpose |
|---|---|
| http://localhost:5173 | Frontend (Vite dev server) |
| http://localhost:8080/api/v1/system/status | Platform status (backend, database, schema version) |
| http://localhost:8080/swagger-ui.html | Swagger UI: the API contract by default, plus the generated implementation view |
| http://localhost:8080/openapi.yaml | API contract, the source of truth ([`openapi.yaml`](backend/src/main/resources/static/openapi.yaml)) |
| http://localhost:8080/v3/api-docs | Specification generated from the code (verified against the contract by tests) |
| http://localhost:8080/actuator/health | Health check (`/liveness` and `/readiness` below it) |

---

## Testing

```bash
# Backend (from backend/)
./mvnw test        # unit, web slice and ArchUnit tests; no Docker needed
./mvnw verify      # + integration tests against a real PostgreSQL via Testcontainers (Docker must run)

# Frontend (from frontend/)
npm run lint
npm run typecheck
npm test           # Vitest + React Testing Library
npm run build
```

- The tests need **no `.env`**: integration tests start their own PostgreSQL container, and the
  throttling secret is random per test run.
- `./mvnw verify` also runs the API contract tests (`OpenApiContractTest`, `OpenApiContractIT`).
- CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs exactly these checks on every push
  to `main` and on every pull request.
- Test reports: `backend/target/surefire-reports/` (unit) and `backend/target/failsafe-reports/`
  (integration).

### Browser acceptance tests (Playwright)

The W2-16 acceptance scenarios and the administrator bootstrap check run in a real Google Chrome
against the real frontend, backend and PostgreSQL (`frontend/e2e/`). They are not part of
`npm test` or CI yet.

```bash
cd backend && ./mvnw -DskipTests package    # the tests start this jar
cd ../frontend && npm run test:e2e           # Docker running, JAVA_HOME set, Google Chrome installed
```

What the tests use:

- **Their own isolated stack:** a throwaway PostgreSQL container `casino-e2e-pg` on port 5441, the
  backend on 8091 and Vite on `127.0.0.1:5174`, with random secrets that are never printed. The
  bootstrap test restarts the backend with a throwaway administrator that exists only in that
  container. Your local
  database, `.env` and dev servers on 5432/8080/5173 are not used. The container and its data are
  removed afterwards.
- **The installed Chrome** (`channel: 'chrome'`), so no Playwright browser download is needed.
- **Real waits:** the session-expiry scenario waits for a real 1-minute idle timeout, and some tests
  restart the backend, so a run takes about 5 minutes.
- **Reports:** failures leave a screenshot and a trace in `frontend/test-results/`; the HTML report
  is in `frontend/playwright-report/` (`npx playwright show-report`). Both are git-ignored. Traces
  contain the disposable test passwords.

## Configuration reference

**Backend** variables are read from the environment or from `.env` (see
[`application.yml`](backend/src/main/resources/application.yml)). **Required** means that the
backend or Compose does not start without a value. Values of secrets belong only in `.env` locally
and in the hosting provider's secret store in production.

| Variable | Used by | Required | Default | Description |
|---|---|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Docker Compose | **yes** | — | Database container settings; the password is a secret. They take effect only when the volume is created. |
| `POSTGRES_PORT` | Docker Compose | no | `5432` | Host port, bound to `127.0.0.1` only. Change `DB_URL` with it. |
| `DB_URL` | Backend | no | `jdbc:postgresql://localhost:5432/casino` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | Backend | **yes** | — | Database credentials (password = secret) |
| `APP_SECURITY_THROTTLE_SECRET` | Backend | **yes** | — | **Secret.** Base64, at least 32 random bytes; HMAC key of the throttle counters ([database.md](docs/database.md#throttling-secret)) |
| `SERVER_PORT` | Backend | no | `8080` | HTTP port (also change `VITE_DEV_PROXY_TARGET`) |
| `APP_CORS_ALLOWED_ORIGINS` | Backend | no | `http://localhost:5173` | Comma-separated browser origins allowed to call the API |
| `APP_SESSION_COOKIE_NAME` | Backend | no | `SESSION` | Production: `__Host-SESSION` |
| `APP_SESSION_COOKIE_SECURE` | Backend | no | `false` | Production: `true` (HTTPS) |
| `APP_SESSION_COOKIE_SAME_SITE` | Backend | no | `lax` | `SameSite` of the session cookie |
| `APP_SESSION_TIMEOUT` | Backend | no | `30m` | Inactivity timeout of a session |
| `APP_SESSION_ABSOLUTE_TIMEOUT` | Backend | no | `12h` | Maximum session lifetime after sign-in |
| `APP_SECURITY_BCRYPT_STRENGTH` | Backend | no | `12` | BCrypt cost (at least 10) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Backend | no | `none` | `none` ignores `X-Forwarded-*`. `native` requires `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`, and `framework` is rejected at start-up. |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | Backend | with `native` only | — | Regular expression matching only the hosting platform's proxies (Week 12) |
| `APP_BOOTSTRAP_ADMIN_EMAIL` / `APP_BOOTSTRAP_ADMIN_PASSWORD` | Backend | no (both or neither) | empty = disabled | First administrator; password = secret; remove after the first start |
| `VITE_API_BASE_URL` | Frontend build | no | empty (same origin) | Backend base URL for production builds |
| `VITE_DEV_PROXY_TARGET` | Frontend dev server | no | `http://localhost:8080` | Target of the Vite `/api` proxy |

Frontend variables go into `frontend/.env.local` (git-ignored, template
[`frontend/.env.example`](frontend/.env.example)). Only `VITE_*` variables reach the browser, so
they must never hold secrets.

---

## Troubleshooting

The diagnostic commands below only read state; none of them prints secret values.

| Symptom | Cause and fix | Diagnose with |
|---|---|---|
| `error during connect` / `Cannot connect to the Docker daemon` | Docker Desktop is not running. Start it and wait until it reports "Engine running". | `docker info --format '{{.ServerVersion}}'` |
| Compose: `required variable POSTGRES_… is missing a value` | `.env` is missing, not in the repository root, or lacks that variable. | `ls -a` in the root shows `.env` |
| Container not `(healthy)`, or exits | See its log. | `docker compose ps`, `docker compose logs --tail 50 postgres` |
| `port is already allocated` (database) | Another service uses the port. Set `POSTGRES_PORT` (e.g. `5434`) in `.env` and the same port in `DB_URL`. | `netstat -ano \| grep LISTENING \| grep ':5432 '`, then `tasklist //FI "PID eq <pid>"` |
| `The JAVA_HOME environment variable is not defined correctly` | `JAVA_HOME` is not set in Git Bash. See [Java 21 for Git Bash](#1-java-21-for-git-bash). | `echo "$JAVA_HOME"`, `./mvnw -v` |
| Backend: `Missing throttling secret` / `Invalid throttling secret: …` | The secret is missing, not Base64, shorter than 32 bytes, or in quotes (the message names the reason, never the value). Generate it as in [step 2](#2-configure-the-environment). | `grep -c '^APP_SECURITY_THROTTLE_SECRET=.' .env` |
| Backend: `Connection to localhost:5432 refused` | The database is not running, or `DB_URL` has the wrong port. | `docker compose ps` |
| Backend: `password authentication failed for user` | `DB_USERNAME`/`DB_PASSWORD` differ from the credentials the volume was **created** with (changing `POSTGRES_PASSWORD` later has no effect on an existing volume). Put the original values back into `.env`, or change the password inside the database: `docker exec -it casino-postgres psql -U <user> -d <db>` and then `\password <user>`. | — |
| Backend: `Validate failed: Migrations have failed validation` | An applied migration file was changed. Restore it (`git diff backend/src/main/resources/db/migration`); changes always go into a new migration. | — |
| Backend: `Port 8080 was already in use` | Another instance is running. Stop it, or set `SERVER_PORT` and the same port in `VITE_DEV_PROXY_TARGET`. | `netstat -ano \| grep LISTENING \| grep ':8080 '` |
| Backend uses unexpected settings | It was not started from `backend/` or the repository root, so `.env` was not found, or an exported variable overrides `.env`. | `env \| cut -d= -f1 \| grep -E '^(DB_\|APP_\|SERVER_\|POSTGRES_)'` (names only) |
| Vite: `http proxy error … ECONNREFUSED` | The backend is not running, or `VITE_DEV_PROXY_TARGET` points to the wrong port. | `curl -s http://localhost:8080/api/v1/system/status` |
| Vite: `Port 5173 is already in use` | Another dev server is running; the port is fixed (`strictPort`). Stop the other one. | `netstat -ano \| grep LISTENING \| grep ':5173 '` |
| `403` with body `Invalid CORS request` | The page's origin is not in `APP_CORS_ALLOWED_ORIGINS`, e.g. the app was opened at `http://127.0.0.1:5173`. Use `http://localhost:5173`. | browser address bar |
| `403 CSRF_TOKEN_INVALID` | Unsafe requests need the `XSRF-TOKEN` cookie value in the `X-XSRF-TOKEN` header. The SPA handles this; with curl or Swagger UI, call `GET /api/v1/auth/csrf` first and again after login, registration and logout ([api.md](docs/api.md#authentication-and-csrf-for-clients)). | browser DevTools → Network |
| `401 SESSION_EXPIRED` / signed out unexpectedly | The session ended after 30 min of inactivity or 12 h after sign-in, or the backend database was reset. Sign in again. | — |
| `429 TOO_MANY_ATTEMPTS` | Too many failed logins (5 per account and IP, 50 per IP in 15 min) or registrations (10 per IP and hour). Wait for `Retry-After`. | response header `Retry-After` |
| Integration tests: `Could not find a valid Docker environment` | Docker Desktop is not running. | `docker info --format '{{.ServerVersion}}'` |

**Resetting the local database** (`docker compose down -v`) deletes every local account and all data
in the volume. It is a last resort, not a routine fix.

---

## Limitations and next milestones

Limitations:

- Week 2 is implemented but still **in review**: the manual browser walkthrough and the pull request
  are open (W2-16).
- Sessions of a user are not revoked automatically when their role or status changes; there is no
  feature for that yet (Week 10). The manual runbook is in
  [`docs/database.md`](docs/database.md#session-revocation-runbook).
- No email verification, password reset or MFA (post-MVP).
- Local development only; no deployment yet (Week 12).

Next milestones:

- **Week 3:** virtual wallet with an immutable ledger, and a starting balance on registration.
- **Week 4:** server-side European roulette engine.

## Documentation

- [Architecture](docs/architecture.md) — modules, layers, security, frontend, deployment checklist
- [API](docs/api.md) — conventions, authentication and CSRF, every implemented operation
- [Database](docs/database.md) — migrations, tables, session revocation runbook
- [User scenarios](docs/user-scenarios.md)
- [Architecture decision records](docs/decisions/)
- [Week 2 implementation plan](docs/plans/week-02.md)

## Development workflow

Short-lived feature branches → pull request → automated CI checks → review → merge to `main`.
Tasks are tracked with GitHub Issues/Projects (Backlog → Ready → In Progress → In Review → Testing → Done).
