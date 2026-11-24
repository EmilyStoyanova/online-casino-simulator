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

| Module | Responsibility | Week | Status |
|---|---|---|---|
| `common` | Shared kernel: web/OpenAPI config, clock, platform status, API error format | 1 | implemented |
| `auth` | Registration, login/logout (session cookies), password hashing, roles PLAYER/ADMIN | 2 | implemented |
| `user` | Accounts, profile, educational interests (UI preferences from Week 6) | 2 | implemented |
| `wallet` | Balances, immutable transaction ledger, idempotency ([below](#wallet-module-week-3)) | 3 | implemented, on `main`, deployed to staging |
| `game` | Roulette engine (CSPRNG), bets, rounds, settlement via `wallet` ([below](#game-module-week-4)) | 4 | implemented (W4-04 to W4-08): rules, secure wheel, round persistence, atomic spin service and `POST /api/v1/games/roulette/spins`; on `main` since PR #10, **not deployed** ([`docs/plans/week-04.md`](plans/week-04.md)) |
| `learning` | Modules, quizzes, answer validation, rewards, daily limits | 7–8 | planned |
| `responsibleplay` | Session limits, breaks, self-exclusion, play-time tracking | 9 | planned |
| `admin` | Administrative use cases under `/api/v1/admin/**` | 10 | planned |

### Dependency rules

- `common` depends on no business module; every module may depend on `common`.
- Modules talk to each other only through their **public API**: the module's `service`, `dto` and
  `exception` packages. They never use another module's repositories, entities or controllers.
- Expected direction: `auth → user`, **`wallet → user`** (only `user.dto.UserRegistered`), `game → wallet`,
  `game → responsibleplay`, `learning → wallet`, `admin → (learning, responsibleplay, wallet, user)`.
  Cycles are not allowed; in particular `user` never depends on `auth` or `wallet`.
- Enforcement: ArchUnit rules R1–R14, R16 and R17 in `ModuleArchitectureTest`, run with every build
  (`./mvnw test`, [ADR-0005](decisions/0005-architecture-rules-archunit.md); R1–R12:
  [`docs/plans/week-02.md`](plans/week-02.md) §9, R13–R14: [`docs/plans/week-03.md`](plans/week-03.md) §9,
  R16–R17: [`docs/plans/week-04.md`](plans/week-04.md) §4.6, §4.7):
  - R13: `wallet.service.WalletService` is used only by `wallet`, `game`, `learning` and `admin`;
  - R14: `wallet` uses `user` only through `user.dto`;
  - R16: randomness comes only from an unseeded `java.security.SecureRandom` (no `new Random()`, no
    `Math.random()`, `ThreadLocalRandom`, `SplittableRandom` or default `RandomGenerator`, no seeding);
  - R17: `model` packages depend on no framework (Spring, JPA, Hibernate, servlet) and no other layer;
  - R15 (production code never used the test-only ledger types) was **retired in W4-03** together with
    those types; its id is not reused. `ArchitectureRulesTest` proves that each rule detects its
    violation. A new module must be added to `ArchitectureRules.BUSINESS_MODULES`.

### Layers inside a module

Every module uses the same layered package structure:

```
com.casino.<module>
├── controller/   REST controllers: HTTP mapping, status codes, delegation to services (thin)
├── service/      business logic, validation, transactions
├── repository/   database access (Spring Data JPA repositories, other data-access adapters)
├── entity/       JPA entities — never returned by controllers
├── dto/          request/response records matching the schemas in openapi.yaml
├── model/        plain domain types and pure rules shared by the other layers (since W4-04; no
│                 framework, no other layer: R17; not part of the public API)
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

### Wallet module (Week 3)

Virtual coins only: no deposits, withdrawals or purchases exist. Design and decisions:
[`docs/plans/week-03.md`](plans/week-03.md); schema: [database.md](database.md#wallets-and-ledger-v6-v7);
API: [api.md](api.md#wallet-get-apiv1wallet).

| Package | Classes | Responsibility |
|---|---|---|
| `controller` | `WalletController` | `GET /api/v1/wallet`, `GET /api/v1/wallet/transactions`; the user is the session principal, never a request value |
| `service` | `WalletService` | Public API of the module: `getWallet`, `listTransactions`, `credit`, `debit`, `openWallet`, `getBalance`, `lockWallet` (W4-06: the row lock for the rest of the caller's transaction, `MANDATORY`) |
| | `WalletOpener` | `@EventListener` for `user.dto.UserRegistered` |
| | `TransactionCursor` | Opaque cursor of the history pages |
| `repository` | `WalletRepository` (+ `WalletLocking`), `WalletTransactionRepository` | Spring Data JPA; no delete methods |
| `entity` | `Wallet`, `WalletTransaction` (`@Immutable`), `TransactionType` | JPA mapping of V6 |
| `dto` | `WalletResponse`, `WalletTransactionPage`, `WalletTransactionResponse`, `WalletEntryType`; `WalletOperation`, `WalletOperationResult`, `WalletTransactionType` | Responses (contract schemas) and the internal command API for other modules |
| `config` | `WalletProperties`, `WalletConfig` | `app.wallet.starting-balance`, `app.wallet.lock-timeout` |
| `exception` | `InsufficientFundsException`, `IdempotencyKeyReusedException`, `BalanceLimitExceededException` (`409`), `WalletBusyException` (`503`), `InvalidCursorException` (`400`), `WalletNotFoundException` (`500`) | Stable `ProblemCode`s |

**Users, wallets and ledger.** One wallet per `PLAYER` (`uq_wallets_user_id`), none for `ADMIN`.
A wallet holds the current balance; every change appends exactly one row to the append-only ledger
`wallet_transactions` (signed `amount`, `balance_after`, idempotency key). The balance always equals
the sum of the wallet's entries.

**Opening a wallet at registration (atomic).** `UserAccountService.createAccount` (`@Transactional`)
saves the account and publishes `UserRegistered`. `WalletOpener` is a plain synchronous
`@EventListener` with `@Transactional(propagation = MANDATORY)`: it runs in the registration
transaction and calls `WalletService.openWallet`, which inserts the wallet (`ON CONFLICT DO NOTHING`)
and credits the starting balance as its first entry (`STARTING_BALANCE`, key `starting-balance`).
Account, wallet and entry commit together; any failure rolls the whole registration back
(`500 INTERNAL_ERROR`, no session). Not `@TransactionalEventListener` (after commit an account could
stay without a wallet) and not `@Async`. A repeated event changes nothing. The administrator
bootstrap publishes no event, so administrators get no wallet. Accounts that existed before V6 got
their wallet from Flyway V7, once.

**Starting balance.** `APP_WALLET_STARTING_BALANCE` (default `1000`, validated 1 … 2⁵³ − 1 at
start-up; an invalid value stops the application). It is copied into the starting entry, so a later
change affects only wallets opened afterwards. V7 uses the literal 1,000.

**Credit and debit rules** (`WalletService.credit`/`debit`; internal only, no HTTP operation):
- `amount` > 0 (the direction comes from the method and the type); the type must match the
  direction; `STARTING_BALANCE` is credited only by `openWallet`;
- key `^[a-z0-9][a-z0-9:._-]{0,99}$`, unique per wallet; `reference` optional, 1–100 characters;
- the balance stays within 0 … 2⁵³ − 1: a too large debit is `INSUFFICIENT_FUNDS`, a credit beyond
  the limit (or a `long` overflow) `BALANCE_LIMIT_EXCEEDED`;
- a failed operation writes nothing; exceptions carry no amounts, balances or keys.

**One transaction per operation, in this order:** validate the operation → lock the wallet row →
look up the idempotency key → check the rules → append one entry and set the balance. `credit` and
`debit` join the caller's transaction (`REQUIRED`), so a Week 4 round can debit, credit and store
its round atomically; if the caller's transaction rolls back, the entry and the balance change do
too, and the key is free again.

**Pessimistic locking and timeout.** Hibernate `PESSIMISTIC_WRITE` (`SELECT … FOR NO KEY UPDATE` on
PostgreSQL) on the wallet row, with the hint `jakarta.persistence.lock.timeout` from
`APP_WALLET_LOCK_TIMEOUT` (default `5s`, 100 ms … 1 min). Verified against PostgreSQL: Hibernate
issues `SET LOCAL lock_timeout` for the locking statement only and restores it afterwards; a longer
wait fails with SQLSTATE `55P03`, answered as `WalletBusyException` (`503 SERVICE_UNAVAILABLE`) with
the whole caller transaction rolled back. Readers (`getWallet`, `listTransactions`) take no lock.
One operation locks at most one wallet; a future feature that locks several must lock them in
ascending wallet id order (deadlock rule).

**Idempotency.** The key lookup happens after the lock, so concurrent duplicates are serialized:
the same key with the same type, amount and reference returns the original result
(`replayed = true`) and changes nothing, also after the balance has moved on; the same key with
other values is `IDEMPOTENCY_KEY_REUSED`. The unique constraint stays the last line of defence.

**Immutable ledger.** `WalletTransaction` is `@Immutable` with no setters; the trigger
`trg_wallet_transactions_append_only` rejects `UPDATE` and `DELETE`; foreign keys are
`ON DELETE RESTRICT`. Corrections are new entries.

**Authorization (PLAYER only).** `SecurityConfig`: `/api/v1/wallet` and `/api/v1/wallet/**` require
`ROLE_PLAYER` (before the generic `/api/**` rule): anonymous `401`, administrators `403
ACCESS_DENIED`, also for unknown wallet paths. Both operations are `GET`; `POST`/`PUT`/`DELETE`
answer `405` after the CSRF check. Responses carry `Cache-Control: no-store`.

**History pages (keyset).** `listTransactions` reads `limit + 1` entries of the session's wallet
ordered by id descending (`WHERE wallet_id = ? [AND id < cursor] ORDER BY id DESC`); the extra entry
decides `nextCursor`. Ids increase in commit order per wallet, so following the cursor never repeats
or skips entries while new ones are added. The cursor is URL-safe Base64 of `v1:<id>`, accepted
only in the exact issued form. It is a position, **not a credential and not cryptographically
bound to a user**: the query is always bound to the session's wallet, so a foreign cursor pages only
through the caller's own entries. Responses show only the published types (`WalletEntryType`); a
stored entry of any other type fails the request closed (`500`). `limit` accepts decimal digits only
(`common.exception.DecimalIntegerParameters`).

**Ledger types (since W4-03, Flyway V8).** `STARTING_BALANCE` (credit, only through `openWallet`),
`ROULETTE_STAKE` (debit) and `ROULETTE_PAYOUT` (credit). The command enum
(`wallet.dto.WalletTransactionType`), the stored enum (`wallet.entity.TransactionType`) and the
published enum (`wallet.dto.WalletEntryType`) have exactly these values; `WalletService` maps them with
exhaustive `switch` expressions, so a type added to one enum without the others does not compile. The
database accepts no other type and enforces each sign. The Week 3 test-only types `TEST_CREDIT`/
`TEST_DEBIT`, their test Flyway callback and ArchUnit R15 were removed in W4-03 (option D,
`docs/plans/week-03.md` §7.13): tests run on exactly the production schema, and
`ProductionArtifactIT` fails if either name appears in any packaged class or resource.

**Coverage.** JaCoCo checks `com.casino.wallet.service` in every `./mvnw verify` (CI included):
at least 80 % lines and 70 % branches. Measured in W3-09: 100 % lines (111/111) and 100 % branches
(62/62); after `lockWallet` and the exhaustive type mapping of Week 4 (W4-08): 118/118 lines and 68/68
branches.

### Game module (Week 4)

**Status:** implemented and tested (W4-04 to W4-08); merged into `main` (PR #10) and **not deployed**
(the Render backend runs the Week 3 build, Neon `staging` is at schema V7; rollout runbook:
[infrastructure/roulette-rollout.md](../infrastructure/roulette-rollout.md), not executed). Design and
decisions: [`docs/plans/week-04.md`](plans/week-04.md); schema:
[database.md](database.md#roulette-rounds-and-bets-v9); API:
[api.md](api.md#roulette-spin-post-apiv1gamesroulettespins). Virtual coins only.

| Package | Classes | Responsibility |
|---|---|---|
| `model` | `RouletteBetCategory`, `RouletteBetColor`, `RouletteParity`, `RouletteRange`, `RouletteDozen`, `RouletteNumberColor`; `RouletteSelection` (sealed: one record per category), `RouletteWager`, `RouletteSettlement`; `RouletteRules`; `PlayedRound`, `SettledWager` (the result of a spin) | European roulette rules as pure functions: colors, which selection wins, payouts (stake × 36/2/3 on a win, 0 on a loss), settlement of a spin; integer arithmetic with overflow checks (`Math.multiplyExact`/`addExact`). Plain Java (R17); one set of enums shared by rules, DTOs and entities |
| `service` | `RouletteWheel` (interface), `SecureRandomRouletteWheel` (package-private `@Component`) | The winning number: one unseeded `new SecureRandom()`, `nextInt(37)` (rejection sampling, no modulo bias); the only wheel bean. Tests replace it through the test context only; production has no seed or "fixed number" switch |
| | `RouletteService` (W4-06) | `spin(userId, requestId, wagers)`: one `@Transactional` round (below); the only writer of rounds; limits `MAX_BETS` = 10 and `MAX_AMOUNT` = 1,000 |
| `entity` | `RouletteRound` (`@Immutable`, `Persistable<UUID>`), `RouletteBet` (`@Immutable`) (W4-05) | Mapping of V9 without setters; the player and the ledger entries by id only, no relationship into `user` or `wallet`; bets owned by the round (unidirectional, `CascadeType.PERSIST`), in request order |
| `repository` | `RouletteRoundRepository` (W4-05; plain Spring Data `Repository`) | `saveAndFlush` (W4-06; a database rule fails inside the spin's transaction), `findByUserIdAndRequestId` (bets through an entity graph); no update or delete |
| `dto` | `RouletteSpinRequest`, `RouletteBetRequest` (schema `RouletteBet`), `RouletteSpinResult`, `RouletteSettledBet`; `OneOptionPerCategory`, `DistinctSelections`; `StrictJson` (W4-07) | The contract's request and response shapes with the `model` enums; Bean Validation of limits, the option of each category and duplicate selections; per-field strict JSON values (integers as integer tokens, enum values as exact names), so no global Jackson setting changes |
| `controller` | `RouletteController` (W4-07) | `POST /api/v1/games/roulette/spins`: the player from the session principal, the `Idempotency-Key` as a `UUID`, delegation to `RouletteService.spin`; `200` for a new round and a replay. No balance, number, odds or payout is read from the request |

There is no `config` and no `exception` package: limits are contract constants, and every failure is
an existing wallet or common exception (no new `ProblemCode`).

**One spin = one database transaction** (`RouletteService.spin`, propagation `REQUIRED`; the wallet
operations join it):

```
RouletteController ── session principal, Idempotency-Key, validated bets
└─ RouletteService.spin                                     @Transactional
    1. validate (1–10 bets, 1–1,000 coins each, no selection twice) ... before any lock or write
    2. WalletService.lockWallet(userId) ............................ wallet row lock, bounded wait
    3. findByUserIdAndRequestId: same content → stored round (replay); other content → 409
    4. roundId = UUID.randomUUID(); WalletService.debit(ROULETTE_STAKE, total stake,
       key roulette-round:<roundId>:stake, reference roundId) ..... insufficient funds → 409
    5. RouletteWheel.spin() → RouletteRules.settle ................. only after the stake is accepted
    6. if total payout > 0: WalletService.credit(ROULETTE_PAYOUT, …, key …:payout)
    7. saveAndFlush(round + bets, both ledger entry ids, balance_after, createdAt = clock.instant())
   commit → RouletteSpinResult (200)
```

- **Atomic wallet integration (`game → wallet`).** `game` uses only `wallet.service.WalletService`,
  `wallet.dto` and `wallet.exception` (R3, R13). Stake entry, payout entry, balance change, round and
  bets commit together or not at all; any exception rolls everything back, nothing is compensated by
  hand. A failed spin draws no number and leaves its key unused. The result is built from the stored
  round and sent only after the commit.
- **Locking.** `WalletService.lockWallet` (W4-06, `MANDATORY`) takes the wallet's existing row lock
  (`PESSIMISTIC_WRITE`, `APP_WALLET_LOCK_TIMEOUT`) **before** the round lookup, so requests of one player
  are serialized; the later `debit`/`credit` re-enter the lock the transaction already holds. One spin
  locks one wallet (deadlock rule). Spins of different players do not wait for each other. A lock
  timeout is `503 SERVICE_UNAVAILABLE` with nothing written.
- **Two levels of idempotency.** The client's `Idempotency-Key` (canonical UUID,
  `CanonicalUuidParameters`) is stored as `roulette_rounds.request_id`, unique per player: the same key
  and the same content (the bets as a set of category, option and amount, Q17) return the stored round
  with no wallet operation, draw or write; other content is `409 IDEMPOTENCY_KEY_REUSED`. The ledger
  keys are server-generated from the round id (`roulette-round:<id>:stake|payout`); the client's key
  never reaches the ledger. The unique constraints are the last line of defence.
- **Fairness and transparency.** Every bet has the expected net result −1/37 of its amount (house edge
  1/37 ≈ 2.7027 %, RTP 36/37 ≈ 97.2973 %), proven exactly over all 37 results by `RouletteRulesTest`;
  the chi-square diagnostic of the real wheel is opt-in (`-Droulette.statistics=true`). One request plays
  exactly one round; there is no autoplay, repeat or bonus mechanic.
- **Architecture rules.** R16 (no weak or seeded randomness in production code) and R17 (`model` is
  framework-free and depends on no other layer; `model` is a layer package since ADR-0004 Amendment 1)
  apply to `game` like to every module; R15 is retired (above).
- **Coverage.** JaCoCo also checks `com.casino.game.model` and `com.casino.game.service` (≥ 80 % lines,
  ≥ 70 % branches); measured in W4-08: `game.model` 105/105 lines and 58/58 branches, `game.service`
  64/64 and 32/32.
- **Accepted limitations.** The database proves that a round's ledger entries exist and cannot be
  deleted, not that they are the round's own (owner, type, amount, reference); `RouletteService` as the
  single writer guarantees it and `verify-roulette.sql` detects any violation
  ([database.md](database.md#roulette-rounds-and-bets-v9)). Responsible-play checks (Week 9) will be
  added as step 1a of `spin`, before the lock; Week 4 contains no placeholder for them.

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
  See `docs/plans/week-02.md` §7.10. In that fallback the SPA cannot read the API host's
  `XSRF-TOKEN` cookie unless the cookie is shared with the frontend host; this is part of the
  Week 12 decision.
- Staging: Vercel serves the build and rewrites `/api/*` to the Render backend
  ([`frontend/vercel.json`](../frontend/vercel.json), [runbook](../infrastructure/vercel/README.md)).
  This is the preferred same-site setup; `VITE_API_BASE_URL` stays empty. Answers from a proxy
  instead of the backend are shown as "starting up or temporarily unavailable"
  (`ApiError.isUnavailable`): 502/503/504 without Problem Details, or a 2xx that is not JSON. This
  covers a Render Free cold start that exceeds Vercel's 120 s proxy timeout.

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
| Security | Spring Security and Spring Session JDBC, see [§5.1](#51-security-week-2-extended-in-week-3) and [ADR-0006](decisions/0006-session-authentication-security.md) |
| Architecture rules | ArchUnit `ModuleArchitectureTest` ([ADR-0005](decisions/0005-architecture-rules-archunit.md)), run with the unit tests |
| Testing | Backend: unit (JUnit + Mockito), web slices (`@WebMvcTest` + Spring Security Test), ArchUnit, integration and security tests against PostgreSQL (Testcontainers, `*IT`), contract tests against `openapi.yaml`. Migrations also on PostgreSQL 18.6 (`MigrationCompatibilityIT`). Coverage: JaCoCo, enforced for `com.casino.wallet.service`, `com.casino.game.model` and `com.casino.game.service` (≥ 80 % lines, ≥ 70 % branches). Test contexts are cached at most 6 at a time (`src/test/resources/spring.properties`), because each holds its own PostgreSQL container. Frontend: Vitest + React Testing Library, `fetch` stubbed with contract-shaped responses. Browser acceptance: Playwright against an isolated real stack (`frontend/e2e`, run locally); full E2E coverage and CI in Week 11 |
| CI | GitHub Actions: backend `mvnw verify` (including the JaCoCo check; coverage report uploaded as `backend-coverage`), frontend lint → typecheck → test → build |
| Request parameters | Integer query parameters accept decimal digits only (`common.exception.DecimalIntegerParameters`); `UUID` parameters and headers (the `Idempotency-Key`) only the canonical 36-character form, either case (`common.exception.CanonicalUuidParameters`, W4-07); anything else is `400 MALFORMED_REQUEST`, range violations `400 VALIDATION_FAILED`. Numeric and enum fields of the roulette request body are strict JSON values (`game.dto.StrictJson`): no fraction, exponent, string or position number |

### 5.1 Security (Week 2, extended in Week 3)

Design and rationale: [ADR-0006](decisions/0006-session-authentication-security.md) and
[`docs/plans/week-02.md`](plans/week-02.md) §7. Implemented in `auth.config.SecurityConfig` and the
`auth` services:

- **Authorization:** deny by default.
  - Public: system status, CSRF token, registration, login, logout, interests, health and info,
    Swagger UI and both API descriptions.
  - `/api/v1/admin/**` needs the role `ADMIN` (no endpoints yet). `/api/v1/wallet` and
    `/api/v1/wallet/**` need the role `PLAYER` (Week 3), and so do `/api/v1/games` and
    `/api/v1/games/**` (W4-07). Every other `/api/**` path needs a
    session. Everything else is denied.
  - `401` and `403` are Problem Details from `ProblemDetailFactory`.
- **Authentication:** JSON login and registration endpoints; `DaoAuthenticationProvider` with
  `AccountUserDetailsService`. Every failed login gets the same `401 INVALID_CREDENTIALS`, also for
  unknown and disabled accounts.
  - The session principal is Spring's `User`: the user id as username, `ROLE_PLAYER` or
    `ROLE_ADMIN` from `users.role` at sign-in.
  - Controllers identify the user only through the principal.
- **Sessions:** Spring Session JDBC in PostgreSQL (survives restarts).
  - The cookie is `HttpOnly` and `SameSite=Lax`; `__Host-SESSION` + `Secure` in production.
  - The session id changes at every sign-in. Sessions end after 30 min idle and 12 h after sign-in
    (`AbsoluteSessionTimeoutFilter`).
- **CSRF:** `csrf.spa()`. The `XSRF-TOKEN` cookie value goes into the `X-XSRF-TOKEN` header on every
  unsafe method. Sign-in and logout delete the cookie.
- **CORS:** only the origins in `APP_CORS_ALLOWED_ORIGINS`.
- **Passwords:** BCrypt cost 12 (`DelegatingPasswordEncoder`, `{bcrypt}` prefix). Policy: 12
  characters to 72 UTF-8 bytes, not the email or display name, not on the common-password denylist
  (`auth.dto.CommonPasswords`).
- **Throttling:** fixed windows in `auth_throttle`, checked before the password is verified.
  - Keys are HMACs with `APP_SECURITY_THROTTLE_SECRET`.
  - The client IP is the TCP peer. Forwarded headers are ignored unless the trusted proxies are
    configured (`ForwardedHeadersCheck`).
- **Administrators:** `ADMIN` is never assigned through the API. `AdminBootstrapRunner` creates the
  first one from the environment and never promotes an existing account.
- **Frontend:** `RequireAuth` only shapes the interface; the backend checks every request.
- **Known limitations:**
  - Sessions are not revoked automatically when a role, status or password changes. There is no
    such feature yet; it is mandatory in Week 10, and until then the manual runbook in
    [`database.md`](database.md#session-revocation-runbook) applies.
  - CSRF tokens are not revoked on the server.
  - Method security comes with the admin services (Week 10).

## 6. Deployment view (staging today, final decision Week 12)

| Component | Staging today | Code deployed |
|---|---|---|
| Frontend (static build) | Vercel, `https://online-casino-simulator.vercel.app`, same-origin `/api` rewrite to Render; deploys `main` automatically ([infrastructure/vercel](../infrastructure/vercel/README.md)). Cloudflare Pages was the original plan; the final choice is ADR-0007 | `main`, deployed automatically (Week 4 changed no frontend file) |
| Backend (Docker image, [`backend/Dockerfile`](../backend/Dockerfile)) | Render Free, Frankfurt, `https://online-casino-simulator.onrender.com`, profiles `neon,render`, registration closed, Auto-Deploy Off, manual deploys ([infrastructure/render](../infrastructure/render/README.md)) | the Week 3 build of `main` (manual deploy 2026-11-15) |
| Database | Neon (managed PostgreSQL 18), branch `staging`; `production` untouched until Week 12. Local development and tests use 17 | schema **V7** |

Week 3 (wallet, V6/V7) is **deployed to staging** (2026-11-15): merged into `main` (PR #9), deployed
manually on Render, which applied V6 and V7 to Neon `staging` at start-up. The approved runbook and
the verification results are in
[infrastructure/wallet-rollout.md](../infrastructure/wallet-rollout.md#rollout-record-2026-11-15).
Registration stays closed; Neon `production` is untouched.

Week 4 (roulette, V8/V9) is **not deployed**: it is merged into `main` (PR #10), but Render
Auto-Deploy is Off, so the merge did not change the backend. Its staging rollout
(V7 → V9) needs a separate approval and follows
[infrastructure/roulette-rollout.md](../infrastructure/roulette-rollout.md) (prepared in W4-09, not
executed).

Local development uses Docker Compose for PostgreSQL; the backend and frontend run natively for a
fast feedback loop. The Spring profile `neon` points a local backend at a Neon branch instead
(first tests on a `staging` branch; [database.md → Neon](database.md#neon)).

### Deployment checklist (before public access, Week 12)

The configuration exists already; the checklist makes sure production uses it (from
`docs/plans/week-02.md` §7.4, §7.8 and §13). All values live in the hosting provider's secret store,
never in the repository. On Render, the profile `render` (`application-render.yml`) already sets
item 1, HTTPS-only (HSTS and `Secure` cookies without trusting forwarded headers) and the Swagger
part of 8. Items 2–4 remain per environment, and items 5–6 wait for the verified proxy chain
([infrastructure/render](../infrastructure/render/README.md#client-address)).

1. `APP_SESSION_COOKIE_NAME=__Host-SESSION` and `APP_SESSION_COOKIE_SECURE=true` (HTTPS only).
2. `APP_SECURITY_THROTTLE_SECRET`: a new production value, different from every local one.
3. Database: `SPRING_PROFILES_ACTIVE=neon` with the `NEON_DB_*` values of the production branch's
   direct endpoint (verified TLS, channel binding; [database.md → Neon](database.md#neon)).
   Check the Neon plan's compute hours: Spring Session's cleanup job (every minute by default) keeps
   a scale-to-zero compute awake.
4. `APP_CORS_ALLOWED_ORIGINS`: the production frontend origin only. Also required behind the Vercel
   rewrite, because the browser's `Origin` differs from the backend's `Host` (`VercelProxyIT`).
5. `SERVER_FORWARD_HEADERS_STRATEGY=native` together with an explicit
   `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` that matches only the platform's proxies. Spring Boot's
   default would trust all private address ranges, and the backend refuses to start without the list.
   Render does not document its proxy addresses: until they have been verified from its logs, the
   profile `render` uses `none`, and all clients share the proxy's address for throttling.
6. Verify behind the proxy: the client IP is the right-most untrusted `X-Forwarded-For` entry,
   `request.isSecure()` is `true`, and the cookies carry `Secure`; add a smoke test for the cookie
   attributes over HTTPS.
7. `APP_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD` only for the first start, then remove them.
8. Decide the routing (same site via reverse proxy, or shared custom domain;
   `docs/plans/week-02.md` §7.10) and disable Swagger UI and `/v3/api-docs` in production.
