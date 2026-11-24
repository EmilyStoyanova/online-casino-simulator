# API

The API is **design-first**. The contract
[`backend/src/main/resources/static/openapi.yaml`](../backend/src/main/resources/static/openapi.yaml)
(OpenAPI 3.1) is the source of truth, and this page only summarises it
([ADR-0004](decisions/0004-design-first-api.md)).

| URL (local) | Content |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI. Shows **Contract (openapi.yaml)** by default; **Implementation (generated)** in the top-right selector |
| http://localhost:8080/openapi.yaml | The contract (`application/yaml`) |
| http://localhost:8080/v3/api-docs | Specification generated from the code (implementation view) |

## Conventions

- Base path `/api/v1`; administrative endpoints will use `/api/v1/admin/**` (from Week 10). That path
  already requires the `ADMIN` role: without a session `401`, signed in as `PLAYER` `403 ACCESS_DENIED`
  for every path below it. The role comes from the account at sign-in, never from a request; a role
  change applies from the next sign-in. `ADMIN` is never assigned through the API (first
  administrator: `APP_BOOTSTRAP_ADMIN_EMAIL`/`APP_BOOTSTRAP_ADMIN_PASSWORD`).
- JSON request and response bodies; timestamps are ISO-8601 UTC.
- Errors are RFC 9457 Problem Details (`application/problem+json`, schema `ProblemDetail`) with a
  stable `code` and `Cache-Control: no-store`. They are built by `common.exception.ProblemDetailFactory`
  and produced centrally by `GlobalExceptionHandler`; errors that never reach a controller (e.g. from
  a servlet filter) are forwarded to `/error` and answered the same way by `ErrorFallbackController`.
- Clients decide on `code`, never on `title` or `detail`. Every code has exactly one status, except
  the generic fallbacks `CLIENT_ERROR` and `SERVER_ERROR`; the HTTP status of an error is never changed:

  | `code` | Status | When |
  |---|---|---|
  | `VALIDATION_FAILED` | 400 | Bean Validation failures; `errors` lists `{field, message}` sorted by field, never the rejected value |
  | `MALFORMED_REQUEST` | 400 | Unreadable JSON, wrong JSON types, missing body, missing or unconvertible parameter (integer parameters: decimal digits only, `^-?[0-9]+$`) |
  | `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`, `INVALID_CREDENTIALS` | 401 | No session, ended session, failed login |
  | `ACCESS_DENIED`, `CSRF_TOKEN_INVALID`, `REGISTRATION_DISABLED` | 403 | Missing role (`/api/v1/admin/**`; `/api/v1/wallet/**` and `/api/v1/games/**` for non-players), missing or invalid CSRF token, registration closed by configuration |
  | `NOT_FOUND` | 404 | Unknown path or resource |
  | `METHOD_NOT_ALLOWED` | 405 | Unsupported method (`Allow` header) |
  | `NOT_ACCEPTABLE` | 406 | No representation matches the `Accept` header (the problem itself is still JSON) |
  | `CONFLICT` | 409 | Generic conflict (e.g. a framework `409`) |
  | `EMAIL_ALREADY_REGISTERED`, `CONCURRENT_MODIFICATION` | 409 | Registration with an existing email; overlapping profile update |
  | `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, `BALANCE_LIMIT_EXCEEDED` | 409 | Wallet rules of balance-changing operations (the roulette spin; [wallet](#wallet-rules-both-operations)) |
  | `CONTENT_TOO_LARGE` | 413 | Request too large |
  | `UNSUPPORTED_MEDIA_TYPE` | 415 | Unsupported request `Content-Type` (`Accept` header lists the supported types) |
  | `TOO_MANY_ATTEMPTS` | 429 | Login and registration throttling; `Retry-After` = whole seconds until the window ends |
  | `INTERNAL_ERROR` | 500 | Unexpected failure; generic detail, the exception is only logged |
  | `SERVICE_UNAVAILABLE` | 503 | Temporarily unavailable (e.g. a request timeout) |
  | `CLIENT_ERROR` | other `4xx` | Generic fallback; the status is kept (e.g. `410`, `422`) |
  | `SERVER_ERROR` | other `5xx` | Generic fallback; the status is kept (e.g. `501`, `502`) |

- Module exceptions extend `common.exception.ApiException` (a specific `ProblemCode` and a safe
  detail; the generic fallbacks are rejected). Framework errors (`ResponseStatusException`,
  `413`, `503`, …) keep their status and get the code of that status; their own message is never
  sent. `GET /api/v1/system/status` keeps its `503` with a `SystemStatus` body.
- All input is validated, and authorization and business rules are enforced on the server.
- Access is denied by default (Spring Security). Public operations have `security: []`; every other
  `/api/**` path needs a session: `401 AUTHENTICATION_REQUIRED`, or `401 SESSION_EXPIRED` when the
  session cookie refers to an ended session. An unknown `/api/**` path therefore returns `401`
  without a session and `404` with one. Paths outside `/api/**` that are not listed as public are
  denied (`401`/`403 ACCESS_DENIED`).
- Every `POST`, `PUT`, `PATCH` and `DELETE` needs the CSRF token: the `XSRF-TOKEN` cookie value in the
  `X-XSRF-TOKEN` header (`GET /api/v1/auth/csrf` issues the cookie), otherwise `403 CSRF_TOKEN_INVALID`.
  Public paths accept every method (and `HEAD` wherever `GET` works); an unsupported method returns
  `405 METHOD_NOT_ALLOWED` once the CSRF check has passed.
- **Exception to Problem Details:** a request from an origin that is not in `APP_CORS_ALLOWED_ORIGINS`
  is rejected by Spring's CORS processor before any application code, with `403`, the fixed
  plain-text body `Invalid CORS request` and no CORS headers. The browser does not expose this
  response to the foreign page, so a Problem Details body would have no reader; standardizing it
  would require replacing Spring Security's CORS filter.

## Authentication and CSRF for clients

The API uses **server-side sessions**, not bearer tokens ([ADR-0006](decisions/0006-session-authentication-security.md)).
The session id is in an `HttpOnly` cookie (`SESSION` locally, `__Host-SESSION` in production), so
JavaScript never sees it. The SPA keeps nothing about the session in browser storage.

| Operation | Without a session | Session | CSRF token |
|---|---|---|---|
| `GET /api/v1/system/status`, `GET /api/v1/auth/csrf`, `GET /api/v1/interests` | ✅ | not needed | not needed |
| `POST /api/v1/auth/register`, `/login`, `/logout` | ✅ | not needed | **required** |
| `GET /api/v1/users/me` | `401` | required | not needed |
| `PATCH /api/v1/users/me` | `401` | required | **required** |
| `GET /api/v1/wallet`, `GET /api/v1/wallet/transactions` | `401` | `PLAYER` role, otherwise `403 ACCESS_DENIED` | not needed |
| `POST /api/v1/games/roulette/spins` (since Week 4; not on staging yet) | `401` | `PLAYER` role, otherwise `403 ACCESS_DENIED` | **required** (checked first), plus the `Idempotency-Key` header |
| `/api/v1/admin/**` (no endpoints yet) | `401` | `ADMIN` role, otherwise `403 ACCESS_DENIED` | for unsafe methods |
| `/actuator/health/**`, `/actuator/info`, Swagger UI, `/openapi.yaml`, `/v3/api-docs` | ✅ | not needed | — |

Flow of a client:

1. `GET /api/v1/auth/csrf` → `204` and the `XSRF-TOKEN` cookie.
2. Every `POST`, `PUT`, `PATCH` and `DELETE` sends that cookie value in the `X-XSRF-TOKEN` header.
3. Register or log in → `200`/`201` with the `UserProfile`, a **new** session id, and the
   `XSRF-TOKEN` cookie deleted. Repeat step 1 before the next unsafe request.
4. Logout → `204`, session deleted, `XSRF-TOKEN` deleted.

The SPA does this in `frontend/src/services/apiClient.ts`. It retries once with a new token after
`403 CSRF_TOKEN_INVALID`.

The same flow with curl in Git Bash (the cookie file holds the session; delete it afterwards):

```bash
curl -s -c cookies.txt -b cookies.txt http://localhost:8080/api/v1/auth/csrf
TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' cookies.txt)
curl -s -c cookies.txt -b cookies.txt -H "X-XSRF-TOKEN: $TOKEN" -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"<password>"}' http://localhost:8080/api/v1/auth/login
rm cookies.txt
```

In Swagger UI ("Try it out"), call `GET /api/v1/auth/csrf` first: Swagger UI then sends the token
itself (`springdoc.swagger-ui.csrf.enabled`).

## Workflow for API changes

1. Change `openapi.yaml` (new path, field, status code, …) and review it. Operations designed ahead
   of their implementation carry `x-implementation-status: planned` and the summary suffix
   "(planned)"; schema properties designed ahead carry the same marker, and enum values designed
   ahead are listed in the schema's `x-planned-enum-values` (since 0.5.0). The contract tests skip
   planned elements while they are not implemented, fail as soon as one is implemented with the
   marker still present, never validate responses of planned operations and reject planned enum
   values in responses.
2. Implement the controller (`controller` package) and DTO records (`dto` package), and remove the
   `planned` marker in the same change.
3. Run `./mvnw verify`. `OpenApiContractIT` fails while the implementation and the contract differ,
   and the failure message lists every difference.
4. Update the frontend types in `frontend/src/types/` accordingly.

## Implemented endpoints

| Method | Path | Tag | Responses | Description |
|---|---|---|---|---|
| GET | `/api/v1/system/status` | System | `200` `SystemStatus`, `503` `SystemStatus`, `500` `ProblemDetail` | Application version and database connectivity (public) |
| GET | `/api/v1/auth/csrf` | Authentication | `204` (`XSRF-TOKEN` cookie), `500` `ProblemDetail` | CSRF token for unsafe requests (public) |
| POST | `/api/v1/auth/register` | Authentication | `201` `UserProfile` (+ session cookie), `400`, `403`, `409`, `429`, `500` `ProblemDetail` | Creates a `PLAYER` account and signs it in (public, CSRF token required; `403 REGISTRATION_DISABLED` while closed) |
| POST | `/api/v1/auth/login` | Authentication | `200` `UserProfile` (+ session cookie), `400`, `401`, `403`, `429`, `500` `ProblemDetail` | Signs in (public, CSRF token required) |
| POST | `/api/v1/auth/logout` | Authentication | `204`, `403`, `500` `ProblemDetail` | Ends the session (public, CSRF token required) |
| GET | `/api/v1/interests` | Interests | `200` `Interest[]`, `500` `ProblemDetail` | Active educational interests in display order (public) |
| GET | `/api/v1/users/me` | Users | `200` `UserProfile`, `401`, `500` `ProblemDetail` | Profile of the signed-in user (session required) |
| PATCH | `/api/v1/users/me` | Users | `200` `UserProfile`, `400`, `401`, `403`, `409`, `500` `ProblemDetail` | Updates the display name and/or interests (session and CSRF token required) |
| GET | `/api/v1/wallet` | Wallet | `200` `Wallet`, `401`, `403`, `500` `ProblemDetail` | Balance of the signed-in player's wallet (`PLAYER` role; read-only) |
| GET | `/api/v1/wallet/transactions` | Wallet | `200` `WalletTransactionPage`, `400`, `401`, `403`, `500` `ProblemDetail` | Ledger entries of that wallet, newest first, keyset pages (`PLAYER` role; read-only) |
| POST | `/api/v1/games/roulette/spins` | Roulette | `200` `RouletteSpinResult`, `400`, `401`, `403`, `409`, `500`, `503` `ProblemDetail` | Places 1–10 bets and plays one round of European roulette (`PLAYER` role, CSRF token and `Idempotency-Key` required) |
| GET | `/actuator/health` | Operations | `200` / `503` `HealthStatus` | Aggregated health incl. health groups |
| GET | `/actuator/health/liveness` | Operations | `200` / `503` `HealthStatus` | Liveness probe |
| GET | `/actuator/health/readiness` | Operations | `200` / `503` `HealthStatus` | Readiness probe |
| GET | `/actuator/info` | Operations | `200` object | Application info (currently empty) |

Example `GET /api/v1/system/status` → `200`:

```json
{
  "application": "online-casino-simulator",
  "version": "0.1.0-SNAPSHOT",
  "status": "UP",
  "database": { "status": "UP", "schemaVersion": "7" },
  "timestamp": "2026-10-29T10:15:30.123Z"
}
```

### System status (`GET /api/v1/system/status`)

- Public. `200` when the backend and the database are up; `503` with the same `SystemStatus` body
  when the database is unreachable (`database.status` = `DOWN`, `schemaVersion` = `null`).
- `schemaVersion` is the latest applied Flyway migration: `9` for a build of `main` since Week 4
  (local database, Testcontainers); `7` on staging (since the Week 3 rollout of
  2026-11-15, [runbook](../infrastructure/wallet-rollout.md)). Staging reaches `9` only through the
  separately approved [Week 4 rollout](../infrastructure/roulette-rollout.md).

### CSRF token (`GET /api/v1/auth/csrf`)

- Public. `204` without a body; the response sets the `XSRF-TOKEN` cookie (readable by JavaScript,
  `Path=/`). See [Authentication and CSRF for clients](#authentication-and-csrf-for-clients).
- The token is a stateless double-submit value: the server compares the cookie with the
  `X-XSRF-TOKEN` header and stores nothing.

### Registration (`POST /api/v1/auth/register`)

```json
{ "email": "alice@example.com", "password": "correct-horse-battery", "displayName": "Alice", "adultConfirmed": true }
```

- `201` with `UserProfile` (`id`, `email`, `displayName`, `role` = `PLAYER`, `interests` = `[]`,
  `adultConfirmedAt` from the server clock, `createdAt`). The user is signed in: a new session id
  in the session cookie, and the CSRF token is cleared, so the client calls `GET /api/v1/auth/csrf`
  again.
- Validation (`400 VALIDATION_FAILED`, every invalid property in one response): `email` trimmed,
  well-formed, ASCII, ≤ 254 characters; `password` 12 characters to 72 UTF-8 bytes, not equal to
  the email or display name (ignoring case), not a commonly used password (`must not be a commonly
  used password`; 15,535-entry denylist from a public-domain dataset, ignoring case and surrounding
  whitespace, see `backend/src/main/resources/auth/common-passwords-NOTICE.md`); `displayName`
  trimmed, 3–30 characters, no control characters; `adultConfirmed` must be `true`. Rejected values
  are never echoed.
- The email is lower-cased before the duplicate check and storage; an existing email (also a case
  variant, also a concurrent request) → `409 EMAIL_ALREADY_REGISTERED`, without a session.
- Other properties (`role`, `status`, `id`, …) are ignored. The password is stored only as a BCrypt
  hash and never logged or returned.
- At most 10 attempts per client IP and hour → `429 TOO_MANY_ATTEMPTS` with `Retry-After`. Every
  request that passes the CSRF check counts (`201`, `400`, `409`); the limit is checked before the
  body, so the order is `403` → `429` → `400` → `409`. The client IP is the TCP peer; forwarded
  headers are ignored unless a trusted proxy is configured.
- The registration response deletes the `XSRF-TOKEN` cookie; the client calls `GET /api/v1/auth/csrf`
  before its next unsafe request.
- **Closed registration:** with `APP_REGISTRATION_ENABLED=false` (server configuration only; the
  default is `true`, and `false` for the Render profile), every request that passes the CSRF check
  gets `403 REGISTRATION_DISABLED`. The order is then `403 CSRF_TOKEN_INVALID` →
  `403 REGISTRATION_DISABLED`. Nothing is counted, no body is read, no account or session is
  created. Login, logout and sessions are unaffected. The registration form shows "New registrations
  are currently closed" with a link to sign in.

### Login and logout (`POST /api/v1/auth/login`, `POST /api/v1/auth/logout`)

```json
{ "email": "alice@example.com", "password": "correct-horse-battery" }
```

- Login `200` with `UserProfile`; a new session id in the session cookie, `XSRF-TOKEN` deleted.
  `400 VALIDATION_FAILED` only for a missing email or password (or an email over 254 characters).
- Every failed login (unknown email, wrong password, disabled account) is the same
  `401 INVALID_CREDENTIALS`. Failed logins are limited to 5 per account and client IP and 50 per
  client IP within 15 minutes → `429 TOO_MANY_ATTEMPTS` with `Retry-After`, checked before the
  password and identical for unknown accounts. No account lockout; a success resets the account and
  IP counter.
- Logout `204`: the session is deleted, the session cookie expires, `XSRF-TOKEN` is deleted. The old
  session cookie then gets `401 SESSION_EXPIRED`. Also `204` without a session. Requires the CSRF
  token, like login.
- A session ends after 30 minutes of inactivity or 12 hours after sign-in
  (`APP_SESSION_TIMEOUT`, `APP_SESSION_ABSOLUTE_TIMEOUT`); a protected operation then answers
  `401 SESSION_EXPIRED`.
- CSRF tokens are stateless double-submit values: deleting the cookie makes the browser forget the
  previous token, but the server does not revoke it (ADR-0006, known limitations).

### Interests (`GET /api/v1/interests`)

```json
[
  { "code": "PROBABILITY", "name": "Probability & odds", "description": "How likely outcomes are and how odds are expressed" },
  { "code": "MATHEMATICS", "name": "Mathematics of games", "description": "Expected value, house edge and variance" }
]
```

- `200` with the active interests of the catalogue (8 seeded by migration V5), ordered by their
  display order, then by code; the order and the codes are stable.
- Public: the same response without a session, with one, or with an ended one; no session is
  created. Only `code`, `name` and `description` are returned.
- Users select 0–8 of these codes in their profile (`PATCH /api/v1/users/me`).

### Profile (`GET /api/v1/users/me`, `PATCH /api/v1/users/me`)

```json
{ "displayName": "Alice B.", "interests": ["PROBABILITY", "STATISTICS"] }
```

- Both require a session; the user is always the one of the session (no user id in the path or the
  body). Without a session `401 AUTHENTICATION_REQUIRED`, with an ended one `401 SESSION_EXPIRED`.
  The SPA calls `GET` at start-up to restore the signed-in user after a page reload.
- `GET` `200` returns the `UserProfile` (as registration and login do), with the selected active
  interests in catalogue order.
- `PATCH` changes only `displayName` and/or `interests`; omitted properties and `null` leave a value
  unchanged, `"interests": []` removes all, other properties (`email`, `role`, …) are ignored.
  Requires the CSRF token. `200` returns the updated profile.
- Validation (`400 VALIDATION_FAILED`): display name as at registration (trimmed, 3–30 characters,
  no control characters); at most 8 interests, no duplicates; unknown or inactive codes are reported
  per position (`interests[1]`), without echoing the code. A rejected request changes nothing.
- An update that overlaps with another change of the same profile → `409 CONCURRENT_MODIFICATION`:
  reload the profile and try again.

### Wallet (`GET /api/v1/wallet`)

```json
{ "balance": 1000, "updatedAt": "2026-11-12T09:15:00.123456Z" }
```

- Players only: without a session `401 AUTHENTICATION_REQUIRED` (ended session `401 SESSION_EXPIRED`),
  an administrator `403 ACCESS_DENIED`, also for any other path under `/api/v1/wallet`.
- The wallet is always the session user's. No parameter, path segment or body selects a wallet;
  query parameters are ignored and `/api/v1/wallet/{id}` is an unknown path (`404`).
- `balance` is a whole number of virtual coins (0 … 2⁵³ − 1, exact in JavaScript); `updatedAt` is
  when it last changed (UTC). No ids are returned. `Cache-Control: no-store`.
- Read-only: it never creates or changes a wallet. A player account without a wallet (a data error)
  gets `500 INTERNAL_ERROR` with the generic detail; the server logs the user id. `POST`, `PUT` and
  `DELETE` answer `405` (after the CSRF check): no public operation changes a balance.
- Every player has a wallet: registration opens it with the starting balance in the same transaction
  (1,000 coins by default), and Flyway V7 opened one for each player who existed before
  ([database.md](database.md#wallets-and-ledger-v6-v7)).

### Wallet history (`GET /api/v1/wallet/transactions`)

```json
{
  "items": [
    { "id": 1, "type": "STARTING_BALANCE", "amount": 1000, "balanceAfter": 1000, "createdAt": "2026-11-12T09:15:00Z" }
  ],
  "nextCursor": null
}
```

- `?limit=` 1–100 (default 20), `?cursor=` the previous page's `nextCursor`. Every page has `items`
  (newest first) and `nextCursor` (always present, `null` on the last page).
- The cursor is opaque. Today it encodes the position of the page's oldest entry, which may change.
  It is **not cryptographically bound to a user or session** (no signature): it is a position, not a
  credential. The history always reads the session user's wallet, so a cursor taken from another
  account only pages through the caller's own entries and reveals nothing else.
- Only the entry types of the contract are ever returned (`WalletTransactionType`); an entry of any
  other type fails the request with a generic `500` instead of being shown.
- `limit` accepts decimal digits only. `5`, `20` or `007` are accepted; `ten`, `1.5`, `1e2`, `0x10`,
  `+5` or ` 5` are `400 MALFORMED_REQUEST`; `0` and `101` are `400 VALIDATION_FAILED` with the field
  error `limit` (`must be greater than or equal to 1` / `must be less than or equal to 100`).

### Wallet rules (both operations)

- **Whose wallet:** always the session user's. No operation takes a wallet or user id, so there is
  nothing to guess (no path parameters, no id parameters).
- **Players only:** session required (`401 AUTHENTICATION_REQUIRED`/`SESSION_EXPIRED`); a signed-in
  administrator gets `403 ACCESS_DENIED`, because administrators have no wallet. Safe methods: no
  CSRF token. Responses carry `Cache-Control: no-store`.
- **Read-only:** no wallet operation credits or debits a wallet. Balances change on the server only, as the
  result of game, learning or reward rules: since W4-07 a [roulette spin](#roulette-spin-post-apiv1gamesroulettespins)
  debits its stake and credits its payout.
- **Coins** are JSON integers (`int64`) from `0` to `9007199254740991` (2^53 − 1), exact in
  JavaScript. A ledger entry has `id`, `type`, `amount` (positive = credit, negative = debit, never
  `0`), `balanceAfter` and `createdAt`; it never contains the idempotency key, the wallet id or the
  user id. Types: `STARTING_BALANCE` (credit, once per wallet), `ROULETTE_STAKE` (debit, the total
  stake of a roulette round) and `ROULETTE_PAYOUT` (credit, its total payout, only when something was
  won); the last two are implemented since W4-03 and appear once rounds are played. Clients treat
  unknown types as generic entries and use the sign of `amount`.
- **Pagination (keyset):** entries are ordered by `id` descending and new entries always get higher
  ids, so following the cursor never repeats or skips entries, also while new ones are added (they
  appear on a new first page). Ids can have gaps.
- **Errors:** `400 VALIDATION_FAILED` for `limit` outside 1–100 or a cursor not issued by the API
  (`errors[].field` = `limit`/`cursor`), `400 MALFORMED_REQUEST` for a `limit` that is not a decimal
  integer, `500 INTERNAL_ERROR` also for a player account without a wallet (a data error; a read
  never creates a wallet).

**Wallet error codes for balance-changing operations (from Week 4).** Returned by the roulette spin
(since W4-07). They were defined in 0.4.0 so the wallet service uses stable codes from the start:

| `code` | Status | When |
|---|---|---|
| `INSUFFICIENT_FUNDS` | 409 | A debit larger than the balance; nothing changes |
| `IDEMPOTENCY_KEY_REUSED` | 409 | An operation key reused with different values; nothing changes |
| `BALANCE_LIMIT_EXCEEDED` | 409 | A credit would exceed 2^53 − 1; nothing changes |

### Roulette spin (`POST /api/v1/games/roulette/spins`)

Implemented in W4-07 (contract 0.5.0) and merged into `main` with Week 4 (PR #10); **not deployed**
(the staging backend runs the Week 3 build; rollout:
[infrastructure/roulette-rollout.md](../infrastructure/roulette-rollout.md), not executed). Design and implementation record: [`docs/plans/week-04.md`](plans/week-04.md) §4, §6, §7,
§8. The contract operation is `spinRoulette`, tag `Roulette`; security `sessionCookie` + `csrfHeader`.

```http
POST /api/v1/games/roulette/spins
Content-Type: application/json
X-XSRF-TOKEN: <token>
Idempotency-Key: 3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b

{ "bets": [ { "category": "STRAIGHT", "number": 17, "amount": 10 }, { "category": "RED_BLACK", "color": "RED", "amount": 20 } ] }
```

`200`:

```json
{
  "roundId": "6f1c2a0e-8b3d-4c55-9a7e-2f0d5b1e9c41",
  "winningNumber": 17,
  "winningColor": "BLACK",
  "bets": [
    { "category": "STRAIGHT", "number": 17, "amount": 10, "payout": 360 },
    { "category": "RED_BLACK", "color": "RED", "amount": 20, "payout": 0 }
  ],
  "totalStake": 30,
  "totalPayout": 360,
  "balance": 1330,
  "createdAt": "2026-11-20T10:15:30.123456Z"
}
```

| Category | Option property and values | P(win) | Net winnings | Payout on a win (stake included) |
|---|---|---|---|---|
| `STRAIGHT` | `number` 0–36 | 1/37 | 35:1 | 36 × amount |
| `RED_BLACK` | `color` `RED`/`BLACK` | 18/37 | 1:1 | 2 × amount |
| `EVEN_ODD` | `parity` `EVEN`/`ODD` | 18/37 | 1:1 | 2 × amount |
| `LOW_HIGH` | `range` `LOW` (1–18)/`HIGH` (19–36) | 18/37 | 1:1 | 2 × amount |
| `DOZEN` | `dozen` `FIRST`/`SECOND`/`THIRD` | 12/37 | 2:1 | 3 × amount |

- **Rules:** zero loses every category except a straight bet on `0`. Every bet's expected net result
  is −1/37 of its amount: house edge 1/37 ≈ 2.7027 %, theoretical RTP 36/37 ≈ 97.2973 %.
- **Who:** players only, from the session (`401 AUTHENTICATION_REQUIRED`/`SESSION_EXPIRED`, an
  administrator `403 ACCESS_DENIED`, also for any other path under `/api/v1/games`); CSRF token required
  (`403 CSRF_TOKEN_INVALID`, checked first). `Cache-Control: no-store` on every response.
- **Server-authoritative:** the request carries bets only. The server draws the number
  (`SecureRandom`), computes the payouts and the balance; unknown properties such as `winningNumber`,
  `payout`, `balance` or `userId` are ignored and change nothing.
- **Bets:** 1–10, each a `category`, exactly the option property of that category (the others omitted
  or `null`) and a whole-coin `amount` of 1–1,000; the same selection at most once (complementary ones
  such as red and black are allowed). The total stake must not exceed the balance
  (`409 INSUFFICIENT_FUNDS`, checked under the wallet lock; nothing is written and no number drawn).
- **Strict JSON values:** `amount` and `number` must be JSON integers: `10.5`, `10.0`, `1e1`, `"10"`,
  booleans, arrays or objects are `400 MALFORMED_REQUEST`, never rounded or converted; `amount: null`
  too. Category and option values must be the exact names as strings (`"COLUMN"`, color `"GREEN"`, `"red"` or `0` →
  `400 MALFORMED_REQUEST`).
- **Validation (`400 VALIDATION_FAILED`, `errors[].field`):** `bets` (none, more than 10, missing, the
  same selection twice); `bets[i].amount` (outside 1–1,000 or missing); `bets[i].category` (missing);
  `bets[i].number` (outside 0–36); the missing option (`bets[i].color` … "must not be null"); an option
  property of another category (`bets[i].dozen` … "must be omitted for this category").
- **`Idempotency-Key`:** required, a canonical UUID (36 characters `8-4-4-4-12`, either case; upper and
  lower case are the same key). Missing, `1-1-1-1-1`, without hyphens, in braces or with spaces →
  `400 MALFORMED_REQUEST`. Generate one per spin attempt and reuse it for its retries.
- **Replay:** the same key with the same bets (as a set: order, `null` options and unknown properties do
  not matter) returns the original `200` body, with the original `balance` and `createdAt`, without any
  wallet operation or draw. The same key with different bets → `409 IDEMPOTENCY_KEY_REUSED`. Keys are
  per player. A rejected request (`400`, `409`, `500`, `503`) records nothing, so its key stays unused.
- **Other errors:** `409 BALANCE_LIMIT_EXCEEDED` (payout beyond 2⁵³ − 1), `500 INTERNAL_ERROR`
  (generic), `503 SERVICE_UNAVAILABLE` (wallet lock timeout; retry with the same key).
- The round's ledger entries appear in the [wallet history](#wallet-history-get-apiv1wallettransactions)
  as `ROULETTE_STAKE` (−total stake) and, when something was won, `ROULETTE_PAYOUT` (+total payout).

**Payout calculation.** Odds are net winnings; `payout` is what the wallet credits back for a bet
(stake included):

```
payout(bet)  = amount × (net odds + 1)  if the bet covers the winning number, else 0
                       (STRAIGHT 36, RED_BLACK / EVEN_ODD / LOW_HIGH 2, DOZEN 3)
totalStake   = Σ amount          → one ROULETTE_STAKE entry of −totalStake
totalPayout  = Σ payout          → one ROULETTE_PAYOUT entry of +totalPayout, only if > 0
balance      = balance before − totalStake + totalPayout
```

For the example above (balance 1,000, winning number 17, which is black): the straight bet on 17 pays
10 × 36 = 360 (net winnings 350), the red bet pays 0 (net −20); `totalStake` 30, `totalPayout` 360, net
change +330. The wallet debits 30 (balance 970) and then credits 360 (balance 1,330). On zero the same
two bets would both lose: `totalPayout` 0, no payout entry, balance 970. All amounts are whole coins;
with the limits a bet pays at most 36,000 and a spin at most 360,000 coins.

**Wallet balance and history after the round** (`GET /api/v1/wallet` → `"balance": 1330`):

```json
{
  "items": [
    { "id": 3, "type": "ROULETTE_PAYOUT", "amount": 360, "balanceAfter": 1330, "createdAt": "2026-11-20T10:15:30.118204Z" },
    { "id": 2, "type": "ROULETTE_STAKE", "amount": -30, "balanceAfter": 970, "createdAt": "2026-11-20T10:15:30.118204Z" },
    { "id": 1, "type": "STARTING_BALANCE", "amount": 1000, "balanceAfter": 1000, "createdAt": "2026-11-12T09:15:00Z" }
  ],
  "nextCursor": null
}
```

A losing round adds only the `ROULETTE_STAKE` entry. Entries never contain the round id, the ledger key
or the client's `Idempotency-Key`; a replay adds no entry.

**Idempotent replay** (same player, same key):

| Second request with the same key | Result |
|---|---|
| Same bets, also reordered, with an option sent as `null`, or the key in upper case | `200` with the **identical** body (same `roundId`, `winningNumber`, payouts, `balance`, `createdAt`), even if the balance has changed since; no debit, credit, draw or write |
| Other bets or amounts | `409 IDEMPOTENCY_KEY_REUSED`; nothing changes |
| Sent while the first is still running | Waits for the wallet lock, then returns the first request's result |
| After the first failed (`400`, `409`, `500`, `503`) | Processed as a new request: the failed one stored nothing |

A client that gets no answer (timeout, proxy `502`) retries with the **same** key: it either receives
the committed round or plays it once. A new spin always needs a new key.

**Error responses** (all Problem Details with `Cache-Control: no-store`; order of checks: CSRF →
authentication → role → header and body → wallet rules):

| Case | Status | `code` | `errors[].field` |
|---|---|---|---|
| Missing or invalid CSRF token | 403 | `CSRF_TOKEN_INVALID` | — |
| No session / ended session | 401 | `AUTHENTICATION_REQUIRED` / `SESSION_EXPIRED` | — |
| Administrator | 403 | `ACCESS_DENIED` | — |
| `Idempotency-Key` missing or not canonical | 400 | `MALFORMED_REQUEST` | — |
| Unreadable or missing body, wrong JSON type, unknown category or option, non-integer `amount`/`number`, `amount: null` | 400 | `MALFORMED_REQUEST` | — |
| No bets, more than 10, the same selection twice | 400 | `VALIDATION_FAILED` | `bets` |
| `amount` outside 1–1,000 or missing | 400 | `VALIDATION_FAILED` | `bets[i].amount` |
| Missing category | 400 | `VALIDATION_FAILED` | `bets[i].category` |
| Missing option, `number` outside 0–36, option of another category | 400 | `VALIDATION_FAILED` | `bets[i].number`, `bets[i].color`, `bets[i].parity`, `bets[i].range`, `bets[i].dozen` |
| Total stake above the balance | 409 | `INSUFFICIENT_FUNDS` | — |
| Key reused with other bets | 409 | `IDEMPOTENCY_KEY_REUSED` | — |
| Payout beyond 2⁵³ − 1 | 409 | `BALANCE_LIMIT_EXCEEDED` | — |
| Unexpected failure (also a player without a wallet) | 500 | `INTERNAL_ERROR` | — |
| Wallet lock timeout | 503 | `SERVICE_UNAVAILABLE` | — |

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The request contains invalid values.",
  "instance": "/api/v1/games/roulette/spins",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "bets[1].color", "message": "must not be null" }
  ]
}
```

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "The virtual balance is too low for this action.",
  "instance": "/api/v1/games/roulette/spins",
  "code": "INSUFFICIENT_FUNDS"
}
```

Example error (`POST /api/v1/system/status` → `405`):

```json
{
  "title": "Method Not Allowed",
  "status": 405,
  "detail": "The HTTP method is not supported by this resource.",
  "instance": "/api/v1/system/status",
  "code": "METHOD_NOT_ALLOWED"
}
```

Example validation error (`400`):

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The request contains invalid values.",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "adultConfirmed", "message": "must be true" },
    { "field": "password", "message": "must be at least 12 characters long" }
  ]
}
```

## Contract history

- **0.2.0 (W2-02):** the Week 2 operations, schemas, error codes and security schemes, marked
  `planned` until they were implemented (W2-04 … W2-10). Design rationale:
  [`docs/plans/week-02.md`](plans/week-02.md) §5.
- **0.3.0 (deployment foundation):** the error code `REGISTRATION_DISABLED` for
  `POST /api/v1/auth/register`.
- **0.4.0 (W3-02):** the two wallet operations, their schemas (`Wallet`, `WalletTransaction`,
  `WalletTransactionType`, `WalletTransactionPage`) and three wallet error codes. Implemented in
  W3-07 and W3-08; no operation is `planned` any more. Design rationale:
  [`docs/plans/week-03.md`](plans/week-03.md) §5.
- **0.5.0 (W4-02, approved 2026-11-15; current on `main` since the Week 4 merge, PR #10; the staging
  backend still serves 0.4.0):**
  `POST /api/v1/games/roulette/spins` (designed as `planned`), its schemas
  (`RouletteSpinRequest`, `RouletteBet`, `RouletteBetCategory` and the option enums,
  `RouletteSpinResult`, `RouletteSettledBet`, `RouletteNumberColor`), the `Idempotency-Key` header,
  and the ledger types `ROULETTE_STAKE`/`ROULETTE_PAYOUT` (designed as `x-planned-enum-values`,
  implemented in W4-03 with Flyway V8, so the listing was removed). No new error code; the existing
  wallet operations are unchanged. The spin is implemented in W4-07; no operation is `planned` any
  more. Design rationale: [`docs/plans/week-04.md`](plans/week-04.md) §4, §6, §8.

## Planned (from the implementation plan)

Each endpoint is added to the contract first, in the week it is implemented.

### Later weeks

| Method | Endpoint | Purpose | Week |
|---|---|---|---|
| GET | `/api/v1/games/roulette/history` | Roulette history | 6 |
| GET | `/api/v1/learning/modules` | Educational modules | 7 |
| POST | `/api/v1/learning/modules/{id}/attempts` | Submit quiz answers | 8 |
| POST | `/api/v1/learning/modules/{id}/rewards` | Claim an eligible reward | 8 |
| GET | `/api/v1/dashboard` | Player statistics | 9 |
| PUT | `/api/v1/play-limits` | Configure play limits | 9 |
