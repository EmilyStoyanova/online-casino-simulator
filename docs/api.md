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

- Base path `/api/v1`; administrative endpoints will use `/api/v1/admin/**`.
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
  | `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`, `INVALID_CREDENTIALS` | 401 | Authentication (Week 2, W2-04 and later) |
  | `ACCESS_DENIED`, `CSRF_TOKEN_INVALID` | 403 | Authorization and CSRF (W2-04 and later) |
  | `NOT_FOUND` | 404 | Unknown path or resource |
  | `METHOD_NOT_ALLOWED` | 405 | Unsupported method (`Allow` header) |
  | `NOT_ACCEPTABLE` | 406 | No representation matches the `Accept` header (the problem itself is still JSON) |
  | `CONFLICT` | 409 | Generic conflict (e.g. a framework `409`) |
  | `EMAIL_ALREADY_REGISTERED`, `CONCURRENT_MODIFICATION` | 409 | Specific conflicts (W2-07, W2-10) |
  | `CONTENT_TOO_LARGE` | 413 | Request too large |
  | `UNSUPPORTED_MEDIA_TYPE` | 415 | Unsupported request `Content-Type` (`Accept` header lists the supported types) |
  | `TOO_MANY_ATTEMPTS` | 429 | Login/registration throttling; `Retry-After` = whole seconds until the window ends (W2-06; used by login and registration from W2-07/W2-08) |
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
  "database": { "status": "UP", "schemaVersion": "1" },
  "timestamp": "2026-10-29T10:15:30.123Z"
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

## Planned (from the implementation plan)

These endpoints are added to the contract first, in the week they are implemented. The Week 2
operations, schemas, error codes and security schemes are already in `openapi.yaml` (contract
version 0.2.0) and marked `planned` (W2-02) until they are implemented (`GET /api/v1/auth/csrf`
since W2-04); the design rationale is in [`docs/plans/week-02.md`](plans/week-02.md) §5.

| Method | Endpoint | Purpose | Week |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Registration with automatic sign-in | 2 |
| POST | `/api/v1/auth/login` | Login (session cookie) | 2 |
| POST | `/api/v1/auth/logout` | Logout | 2 |
| GET | `/api/v1/users/me` | Current user profile | 2 |
| PATCH | `/api/v1/users/me` | Update profile / interests | 2 |
| GET | `/api/v1/interests` | Interest catalogue | 2 |
| GET | `/api/v1/wallet` | Current virtual balance | 3 |
| GET | `/api/v1/wallet/transactions` | Transaction history | 3 |
| POST | `/api/v1/games/roulette/spins` | Place bets and play a round (idempotency key) | 4 |
| GET | `/api/v1/games/roulette/history` | Roulette history | 6 |
| GET | `/api/v1/learning/modules` | Educational modules | 7 |
| POST | `/api/v1/learning/modules/{id}/attempts` | Submit quiz answers | 8 |
| POST | `/api/v1/learning/modules/{id}/rewards` | Claim an eligible reward | 8 |
| GET | `/api/v1/dashboard` | Player statistics | 9 |
| PUT | `/api/v1/play-limits` | Configure play limits | 9 |
