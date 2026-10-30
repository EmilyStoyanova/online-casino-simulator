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
- Modules talk to each other only through their **public API**: the module's `service`, `dto` and
  `exception` packages. They never use another module's repositories, entities or controllers.
- Expected direction: `auth → user`, `game → wallet`, `game → responsibleplay`, `learning → wallet`,
  `admin → (learning, responsibleplay, wallet, user)`. Cycles are not allowed; in particular `user`
  never depends on `auth`.
- Enforcement: ArchUnit rules R1–R12 in `ModuleArchitectureTest`, run with every build
  (`./mvnw test`, [ADR-0005](decisions/0005-architecture-rules-archunit.md),
  [`docs/plans/week-02.md`](plans/week-02.md) §9). `ArchitectureRulesTest` proves that each rule
  detects its violation. A new module must be added to `ArchitectureRules.BUSINESS_MODULES`.

### Layers inside a module

Every module uses the same layered package structure:

```
com.casino.<module>
├── controller/   REST controllers: HTTP mapping, status codes, delegation to services (thin)
├── service/      business logic, validation, transactions
├── repository/   database access (Spring Data JPA repositories, other data-access adapters)
├── entity/       JPA entities — never returned by controllers
├── dto/          request/response records matching the schemas in openapi.yaml
├── config/       module-specific Spring configuration
└── exception/    module exceptions and their HTTP mapping
```

Rules:

- Calls flow one way: `controller → service → repository`. Controllers never use repositories
  directly, and services never depend on controllers.
- Controllers accept and return **DTOs only**; services map between entities and DTOs.
- Constructor injection only.
- Packages are only created when they have content (no placeholder packages).
- Application-wide configuration and the centralised exception handler live in
  `common.config` and `common.exception`.

The `common.system` package (platform status) is the reference implementation:

| Package | Class | Responsibility |
|---|---|---|
| `controller` | `SystemStatusController` | `GET /api/v1/system/status`; maps status to `200`/`503` |
| `service` | `SystemStatusService` | Builds the status from the database check, version and clock |
| `repository` | `DatabaseProbe`, `FlywayDatabaseProbe`, `DatabaseHealth` | Reads Flyway's schema history to check the database |
| `dto` | `SystemStatusResponse`, `ComponentStatus` | API representation (`SystemStatus` schema) |

### API design (design-first)

The HTTP API is designed first in
[`backend/src/main/resources/static/openapi.yaml`](../backend/src/main/resources/static/openapi.yaml),
which is the source of truth. Controllers and DTOs implement it, and contract tests keep them in sync
([ADR-0004](decisions/0004-design-first-api.md)).

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
- Development: the Vite dev server proxies `/api` to the backend (same origin, no CORS).
- Production (decided in Week 12): the preferred setup keeps the API on the **same site** as the
  frontend, through a reverse proxy for `/api/*`. Session cookies then stay first-party, because
  browsers block third-party cookies. As a fallback, `VITE_API_BASE_URL` points to a backend on a
  shared custom domain, and that backend allows the frontend origin via `APP_CORS_ALLOWED_ORIGINS`.
  See `docs/plans/week-02.md` §7.11.

## 5. Cross-cutting concerns

| Concern | Approach |
|---|---|
| Configuration | `application.yml` + environment variables; local `.env` imported via `spring.config.import` |
| Schema management | Flyway migrations in `backend/src/main/resources/db/migration`; Hibernate `ddl-auto=validate` |
| API errors | RFC 9457 Problem Details via `common.exception.GlobalExceptionHandler`; error codes and field errors added in Week 2 |
| API contract | Design-first `openapi.yaml` (source of truth), verified by `OpenApiContractIT` |
| API documentation | Swagger UI at `/swagger-ui.html`, showing the contract and the generated implementation view (to be disabled in production, Week 12) |
| Time | Injected `java.time.Clock` (UTC) for deterministic tests |
| Health | `/actuator/health` (liveness/readiness) and `/api/v1/system/status` |
| Security | *Planned for Week 2* ([ADR-0006](decisions/0006-session-authentication-security.md), proposed): Spring Security (deny by default), Spring Session JDBC (30 min idle, 12 h absolute), `HttpOnly` session cookie (`__Host-` + `Secure` in production), CSRF (SPA pattern), login/registration throttling. Session revocation on admin changes follows in Week 10. |
| Architecture rules | ArchUnit `ModuleArchitectureTest` ([ADR-0005](decisions/0005-architecture-rules-archunit.md)), run with the unit tests |
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
