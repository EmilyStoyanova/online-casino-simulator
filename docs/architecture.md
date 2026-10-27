# Architecture

## 1. Context

The Online Casino Simulator is a **client–server** web application:

```
┌─────────────────────┐   HTTPS / JSON    ┌──────────────────────────────┐   JDBC   ┌────────────┐
│  React SPA (Vite)   │ ───────────────►  │  Spring Boot modular monolith │ ───────► │ PostgreSQL │
│  UI, Phaser visuals │ ◄───────────────  │  business rules, RNG, ledger  │ ◄─────── │  (Flyway)  │
└─────────────────────┘  server-confirmed └──────────────────────────────┘          └────────────┘
                              results
```

Microservices, Kubernetes, message brokers and caches are deliberately **not** used in the first
version ([ADR-0001](decisions/0001-modular-monolith.md)).

## 2. Guiding principles

1. **Server-authoritative game logic.** All random outcomes, payout calculations, balance changes
   and reward validation happen on the backend. The frontend never decides a result
   ([ADR-0003](decisions/0003-server-authoritative-game-logic.md)).
2. **Virtual currency only.** Balances are integers (virtual coins). No real-money flows exist or
   will be modelled.
3. **Correctness of money-like data.** Wallet changes are atomic, idempotent and recorded in an
   immutable ledger; balances can never become negative.
4. **Responsible play is enforced server-side**, not just hidden in the UI.
5. **Explicit module boundaries** inside a single deployable.
6. **Configuration via environment**, no secrets in the repository.

## 3. Backend modules

Root package `com.casino`. Each direct sub-package is a module:

| Module | Responsibility | Week |
|---|---|---|
| `common` | Shared kernel: web/OpenAPI config, clock, platform status, API error format | 1 |
| `auth` | Registration, login/logout (session cookies), password hashing, roles PLAYER/ADMIN | 2 |
| `user` | Profile, educational interests, preferences | 2 |
| `wallet` | Balances, immutable transaction ledger, idempotency | 3 |
| `game` | Roulette engine (CSPRNG), bets, rounds, settlement via `wallet` | 4 |
| `learning` | Modules, quizzes, answer validation, rewards, daily limits | 7–8 |
| `responsibleplay` | Session limits, breaks, self-exclusion, play-time tracking | 9 |
| `admin` | Administrative use cases under `/api/v1/admin/**` | 10 |

### Dependency rules

- `common` depends on no business module; every module may depend on `common`.
- Modules talk to each other only through their **public application API** (services / DTOs in the
  module's `application` package), never through another module's repositories or entities.
- Expected direction: `game → wallet`, `game → responsibleplay`, `learning → wallet`,
  `admin → (learning, responsibleplay, wallet, user)`, `auth ↔ user` via application services.
  Cycles are not allowed.
- Planned enforcement: an automated module-boundary test (Spring Modulith or ArchUnit), to be
  proposed as an ADR once the first modules with cross-module calls exist (Weeks 2–3).

### Layers inside a module

```
com.casino.<module>
├── api/             REST controllers and request/response DTOs (HTTP concerns only)
├── application/     use-case services, ports (interfaces), module-public types
├── domain/          entities, value objects, pure business rules (e.g. payout tables)
└── infrastructure/  adapters: JPA repositories, external integrations, port implementations
```

`api → application → domain`; `infrastructure` implements ports declared in `application`.
Sub-packages are only created when they have content. The `common.system` package (platform status)
is the reference implementation of this layout:

| Layer | Class |
|---|---|
| api | `SystemStatusController`, `SystemStatusResponse` |
| application | `SystemStatusService`, `DatabaseProbe` (port), `SystemStatus`, `DatabaseHealth` |
| infrastructure | `FlywayDatabaseProbe` (adapter) |

## 4. Frontend structure

```
frontend/src
├── components/   reusable presentational components (layout, buttons, …)
├── pages/        route-level screens
├── features/     feature slices: hooks + components per use case (e.g. system-status)
├── games/        Phaser scenes and game rendering (Week 6)
├── services/     API client and per-resource API functions
├── types/        TypeScript types mirroring API contracts
└── test/         test setup and helpers
```

- `services/apiClient.ts` is the only place that calls `fetch`. It sends credentials (session
  cookies from Week 2), maps failures to a typed `ApiError` and supports request cancellation.
- Development: the Vite dev server proxies `/api` to the backend (same origin → no CORS).
  Production: `VITE_API_BASE_URL` points to the backend, which allows the frontend origin via
  `APP_CORS_ALLOWED_ORIGINS`.

## 5. Cross-cutting concerns

| Concern | Approach |
|---|---|
| Configuration | `application.yml` + environment variables; local `.env` imported via `spring.config.import` |
| Schema management | Flyway migrations in `backend/src/main/resources/db/migration`; Hibernate `ddl-auto=validate` |
| API errors | RFC 9457 Problem Details enabled; standardised error model in Week 2 |
| API documentation | springdoc-openapi, `/swagger-ui.html` (to be disabled in production, Week 12) |
| Time | Injected `java.time.Clock` (UTC) for deterministic tests |
| Health | `/actuator/health` (liveness/readiness) and `/api/v1/system/status` |
| Security | Spring Security with session cookies, CSRF protection and role checks from Week 2 |
| Testing | Unit (JUnit + Mockito), web slices (`@WebMvcTest`), integration (Testcontainers PostgreSQL), frontend (Vitest + RTL), E2E (Playwright, Week 11) |
| CI | GitHub Actions: backend `mvnw verify`, frontend lint → typecheck → test → build |

## 6. Deployment view (target, Week 12)

| Component | Host |
|---|---|
| Frontend (static build) | Cloudflare Pages |
| Backend (Docker image) | Render |
| Database | Neon (managed PostgreSQL 17) |

Local development uses Docker Compose for PostgreSQL; the backend and frontend run natively for a
fast feedback loop.
