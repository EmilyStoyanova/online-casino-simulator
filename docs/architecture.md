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
  cookies), maps failures to a typed `ApiError` carrying the Problem Details body (`code`, field
  `errors`, `Retry-After`) and supports request cancellation. Unsafe methods send the CSRF token
  (`XSRF-TOKEN` cookie, read by `services/csrf.ts`) in `X-XSRF-TOKEN`, fetching it from
  `GET /api/v1/auth/csrf` when missing, and retry once on `403 CSRF_TOKEN_INVALID`.
- Routing: React Router 8 (declarative `BrowserRouter`); pages `/`, `/login`, `/register` and the
  protected `/profile`.
- Profile (W2-14): `pages/ProfilePage` shows the saved profile. `features/profile/ProfileForm` edits
  the display name and the interests (`InterestPicker`, catalogue from `GET /api/v1/interests`).
  Each save sends only the changed properties through `AuthProvider.updateProfile`
  (`PATCH /api/v1/users/me`), so the header and the account panel show the result at once. After
  `409 CONCURRENT_MODIFICATION` the user can load the latest version (`reloadProfile`) without
  losing their edits.
- Authentication state (`features/auth/AuthProvider`) lives in memory only. Nothing about the
  user, the password or the session is written to browser storage; the session id stays in the
  `HttpOnly` cookie. After a page load the state is *checking* while `GET /api/v1/users/me`
  (`services/userApi.ts`) asks the server: the profile restores the signed-in user, a `401` means
  signed out, and a network or server error leaves the state *unknown* with a retry (never
  "signed out"). A `401 SESSION_EXPIRED` while signed in clears the state and opens the login page.
  Such a response is ignored if a sign-in or sign-out happened after its request was sent, because
  it concerns the replaced session.
- Protected pages (W2-13): `features/auth/RequireAuth` is a layout route
  (`<Route element={<RequireAuth />}>`). It renders the page only when *authenticated*. *Anonymous*
  users are redirected to `/login?returnTo=<page>`. While *checking* or *unknown* it shows a status
  card: never the page, and no redirect. After sign-in or registration the user returns to
  `returnTo` only if `features/auth/returnPath.ts` accepts it as an internal path; anything else goes
  to `/`. The guard is interface only. The backend checks the session and role on every request and
  remains the security boundary. The first protected page is the profile (W2-14).
- Development: the Vite dev server proxies `/api` to the backend (same origin, no CORS).
- Production (decided in Week 12): the preferred setup keeps the API on the **same site** as the
  frontend, through a reverse proxy for `/api/*`. Session cookies then stay first-party, because
  browsers block third-party cookies. As a fallback, `VITE_API_BASE_URL` points to a backend on a
  shared custom domain, and that backend allows the frontend origin via `APP_CORS_ALLOWED_ORIGINS`.
  See `docs/plans/week-02.md` §7.11. In that fallback the SPA cannot read the API host's
  `XSRF-TOKEN` cookie unless the cookie is shared with the frontend host; this is part of the
  Week 12 decision.

## 5. Cross-cutting concerns

| Concern | Approach |
|---|---|
| Configuration | `application.yml` + environment variables; local `.env` imported via `spring.config.import` |
| Schema management | Flyway migrations in `backend/src/main/resources/db/migration`; Hibernate `ddl-auto=validate` |
| API errors | RFC 9457 Problem Details with a stable `code` (`ProblemCode`), field errors and `Cache-Control: no-store`, built by `common.exception.ProblemDetailFactory`; produced by `GlobalExceptionHandler` and, for errors outside the controllers, by the `/error` fallback (`ErrorFallbackController`). Module exceptions extend `ApiException` |
| API contract | Design-first `openapi.yaml` (source of truth), verified by `OpenApiContractIT` |
| API documentation | Swagger UI at `/swagger-ui.html`, showing the contract and the generated implementation view (to be disabled in production, Week 12) |
| Time | Injected `java.time.Clock` (UTC) for deterministic tests |
| Health | `/actuator/health` (liveness/readiness) and `/api/v1/system/status` |
| Security | [ADR-0006](decisions/0006-session-authentication-security.md) (proposed). **Implemented (W2-04):** Spring Security in `auth.config.SecurityConfig` (deny by default, JSON 401/403 via `ProblemDetailFactory`), Spring Session JDBC (30 min idle), `HttpOnly`/`SameSite=Lax` session cookie (`__Host-` + `Secure` in production), CSRF (`csrf.spa()`, `GET /api/v1/auth/csrf`), CORS via a `CorsConfigurationSource`, BCrypt cost 12. **Implemented (W2-06):** throttling infrastructure (`auth_throttle`, V4; `AuthThrottleService` with fixed windows per account + IP, IP and registration IP; `429 TOO_MANY_ATTEMPTS` + `Retry-After`; hourly cleanup; HMAC-SHA-256 keys with the required `APP_SECURITY_THROTTLE_SECRET`; start-up check of the forwarded-header settings), not yet used by an endpoint. **Implemented (W2-07):** `POST /api/v1/auth/register` with automatic sign-in (session-id rotation, CSRF token cleared), registration throttling and BCrypt hashing. **Implemented (W2-08):** login (`DaoAuthenticationProvider`, identical `401` for every failure, account + IP and IP throttling) and logout, 12 h absolute session lifetime (`AbsoluteSessionTimeoutFilter`). **Implemented (W2-10):** `GET/PATCH /api/v1/users/me` for the session's own user only (principal name = user id), optimistic locking with `409 CONCURRENT_MODIFICATION`. **Implemented (W2-11):** roles `PLAYER`/`ADMIN` from `users.role` at sign-in; `/api/v1/admin/**` → `hasRole("ADMIN")` (anonymous `401`, others `403 ACCESS_DENIED`); first administrator only from `APP_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD` (`AdminBootstrapRunner`, never promotes). **Implemented (W2-12):** common-password denylist in the password policy (`auth.dto.CommonPasswords`, 15,535 entries from a public-domain dataset; source and licence in `backend/src/main/resources/auth/common-passwords-NOTICE.md`), checked only on the server. **Planned:** session revocation on role, status or password changes and method security for the admin services (Week 10). |
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
