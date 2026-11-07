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
  | `MALFORMED_REQUEST` | 400 | Unreadable JSON, wrong JSON types, missing body, missing or unconvertible parameter |
  | `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`, `INVALID_CREDENTIALS` | 401 | No session, ended session, failed login |
  | `ACCESS_DENIED`, `CSRF_TOKEN_INVALID`, `REGISTRATION_DISABLED` | 403 | Missing role (`/api/v1/admin/**`), missing or invalid CSRF token, registration closed by configuration |
  | `NOT_FOUND` | 404 | Unknown path or resource |
  | `METHOD_NOT_ALLOWED` | 405 | Unsupported method (`Allow` header) |
  | `NOT_ACCEPTABLE` | 406 | No representation matches the `Accept` header (the problem itself is still JSON) |
  | `CONFLICT` | 409 | Generic conflict (e.g. a framework `409`) |
  | `EMAIL_ALREADY_REGISTERED`, `CONCURRENT_MODIFICATION` | 409 | Registration with an existing email; overlapping profile update |
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
   "(planned)"; schema properties designed ahead carry the same marker. The contract tests skip
   planned elements while they are not implemented, fail as soon as one is implemented with the
   marker still present, and never validate responses of planned operations.
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
  "database": { "status": "UP", "schemaVersion": "5" },
  "timestamp": "2026-10-29T10:15:30.123Z"
}
```

### System status (`GET /api/v1/system/status`)

- Public. `200` when the backend and the database are up; `503` with the same `SystemStatus` body
  when the database is unreachable (`database.status` = `DOWN`, `schemaVersion` = `null`).
- `schemaVersion` is the latest applied Flyway migration (`5` after Week 2).

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

## Planned (from the implementation plan)

These endpoints are added to the contract first, in the week they are implemented. The Week 2
operations, schemas, error codes and security schemes were added to `openapi.yaml` (contract
version 0.2.0) in W2-02 and marked `planned` until they were implemented (W2-04 … W2-10); every
Week 2 operation is implemented now. The design rationale is in
[`docs/plans/week-02.md`](plans/week-02.md) §5. Contract version 0.3.0 (deployment foundation) adds
the error code `REGISTRATION_DISABLED` to `POST /api/v1/auth/register`.

| Method | Endpoint | Purpose | Week |
|---|---|---|---|
| GET | `/api/v1/wallet` | Current virtual balance | 3 |
| GET | `/api/v1/wallet/transactions` | Transaction history | 3 |
| POST | `/api/v1/games/roulette/spins` | Place bets and play a round (idempotency key) | 4 |
| GET | `/api/v1/games/roulette/history` | Roulette history | 6 |
| GET | `/api/v1/learning/modules` | Educational modules | 7 |
| POST | `/api/v1/learning/modules/{id}/attempts` | Submit quiz answers | 8 |
| POST | `/api/v1/learning/modules/{id}/rewards` | Claim an eligible reward | 8 |
| GET | `/api/v1/dashboard` | Player statistics | 9 |
| PUT | `/api/v1/play-limits` | Configure play limits | 9 |
