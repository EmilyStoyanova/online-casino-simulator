# Online Casino Simulator

A full-stack web application that recreates authentic casino rules and win/loss probabilities —
starting with **European roulette** — using **virtual currency only**. It combines the game with
personalised educational content, quizzes that earn virtual coins, player statistics and
responsible-play tools.

> **No real money, ever.** There are no deposits, withdrawals, purchases of virtual currency or
> cash-equivalent prizes. Virtual coins have no monetary value.
>
> **Not a treatment.** The platform is a simulator and educational tool. It is not presented as a
> clinically validated treatment for gambling addiction.

Built as part of the **SAP mentorship program** following a 12-week implementation plan
([`docs/OnlineCasinoSimulator_ImplementationPlan.pdf`](docs/OnlineCasinoSimulator_ImplementationPlan.pdf)).

---

## Status

| Stage | Weeks | Scope | Status |
|---|---|---|---|
| 1 — Foundation | 1 | Repository, tooling, database, CI, documentation | ✅ Done |
| | 2 | Authentication, users, roles, profile & interests | ⏳ Next |
| 2 — Virtual currency & game logic | 3–4 | Wallet ledger, roulette engine | Planned |
| 3 — Casino UI | 5–6 | Roulette UI, Phaser animations | Planned |
| 4 — Learning & rewards | 7–8 | Learning library, quizzes, rewards | Planned |
| 5 — Statistics & responsible play | 9–10 | Dashboard, limits, admin panel | Planned |
| 6 — QA & release | 11–12 | Testing, deployment, beta release | Planned |

## Architecture at a glance

```
 Browser ──► React + TypeScript (Vite) ──/api/v1──► Spring Boot modular monolith ──► PostgreSQL
             renders server results only            auth · user · wallet · game ·     Flyway-managed
                                                    learning · responsibleplay ·      schema
                                                    admin · common
```

The backend is **authoritative**: it generates every game outcome (cryptographically secure RNG),
calculates payouts and updates balances atomically. The frontend only sends user actions and renders
server-confirmed results. Details: [`docs/architecture.md`](docs/architecture.md).

## Tech stack

| Area | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Web MVC, Spring Data JPA (Hibernate 7), Bean Validation |
| Database | PostgreSQL 17, Flyway 12 migrations |
| API | Design-first OpenAPI 3.1 contract (`openapi.yaml`), springdoc-openapi 3 / Swagger UI |
| Frontend | React 19, TypeScript 6, Vite 8, Tailwind CSS 4 |
| Testing | JUnit (Jupiter), Mockito, Testcontainers, Vitest, React Testing Library |
| Tooling | Maven Wrapper, Docker Compose, GitHub Actions |
| Later stages | Spring Security (W2), Phaser (W6), Playwright (W11), Cloudflare Pages / Render / Neon (W12) |

## Repository layout

```
├── backend/                 Spring Boot application (Maven Wrapper)
│   └── src/main/java/com/casino/<module>/   one package per business module
├── frontend/                React + Vite single-page application
├── infrastructure/          Docker and deployment assets (Weeks 11–12)
├── docs/                    Architecture, API, database, user scenarios, ADRs
├── .github/workflows/       CI pipeline
├── docker-compose.yml       Local PostgreSQL
└── .env.example             Template for local configuration
```

## Getting started

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 (e.g. Eclipse Temurin) | `JAVA_HOME` must point to it. Maven itself is **not** required (wrapper included). |
| Node.js | ≥ 22.12 (24 LTS recommended, see `frontend/.nvmrc`) | Includes npm |
| Docker | Docker Desktop / Engine with Compose v2 | Runs PostgreSQL and the integration tests |

### 1. Configure the environment

```bash
cp .env.example .env          # then change POSTGRES_PASSWORD / DB_PASSWORD
```

`.env` is git-ignored. It is read by Docker Compose and imported by the backend
(`spring.config.import`). Real environment variables always override it.

### 2. Start the database

```bash
docker compose up -d          # PostgreSQL on 127.0.0.1:${POSTGRES_PORT}
```

### 3. Start the backend (http://localhost:8080)

```bash
cd backend
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Flyway applies pending migrations on start-up.

### 4. Start the frontend (http://localhost:5173)

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server proxies `/api/*` to the backend, so no CORS configuration is needed locally.
The home page shows a **Platform status** card that proves the browser → frontend → backend →
database chain works.

### Useful URLs

| URL | Purpose |
|---|---|
| http://localhost:5173 | Frontend |
| http://localhost:8080/api/v1/system/status | Platform status (backend + database + schema version) |
| http://localhost:8080/swagger-ui.html | Swagger UI: the API contract by default, plus the generated implementation view |
| http://localhost:8080/openapi.yaml | API contract, the source of truth ([`backend/src/main/resources/static/openapi.yaml`](backend/src/main/resources/static/openapi.yaml)) |
| http://localhost:8080/v3/api-docs | Specification generated from the code (verified against the contract by tests) |
| http://localhost:8080/actuator/health | Liveness/readiness health check |

## Testing

```bash
# Backend
cd backend
./mvnw test        # unit + web slice tests (no Docker needed)
./mvnw verify      # + integration tests against real PostgreSQL via Testcontainers (Docker required)

# Frontend
cd frontend
npm run lint
npm run typecheck
npm test           # Vitest + React Testing Library
npm run build
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs exactly these checks on every push to
`main` and on every pull request.

## Configuration reference

| Variable | Used by | Default | Description |
|---|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Docker Compose | — (required) | Database container credentials |
| `POSTGRES_PORT` | Docker Compose | `5432` | Host port of the database (change if 5432 is taken) |
| `DB_URL` | Backend | `jdbc:postgresql://localhost:5432/casino` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | Backend | — (required) | Database credentials |
| `SERVER_PORT` | Backend | `8080` | HTTP port |
| `APP_CORS_ALLOWED_ORIGINS` | Backend | `http://localhost:5173` | Comma-separated origins allowed to call the API |
| `VITE_API_BASE_URL` | Frontend | empty (same origin) | Backend base URL for production builds |
| `VITE_DEV_PROXY_TARGET` | Frontend (dev) | `http://localhost:8080` | Target of the Vite `/api` proxy |

## Troubleshooting

- **`port is already allocated` when starting PostgreSQL** — another service uses that port. Set
  `POSTGRES_PORT` (e.g. `5434`) in `.env` and update the port in `DB_URL` accordingly.
- **`password authentication failed`** — `DB_USERNAME`/`DB_PASSWORD` do not match the
  `POSTGRES_*` values the volume was initialised with. Fix `.env`, or reset the local database with
  `docker compose down -v` (deletes local data).
- **Integration tests fail with "Could not find a valid Docker environment"** — start Docker.

## Documentation

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Database](docs/database.md)
- [User scenarios](docs/user-scenarios.md)
- [Architecture decision records](docs/decisions/)

## Development workflow

Short-lived feature branches → pull request → automated CI checks → review → merge to `main`.
Tasks are tracked with GitHub Issues/Projects (Backlog → Ready → In Progress → In Review → Testing → Done).
