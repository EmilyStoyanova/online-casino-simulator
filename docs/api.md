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
- Errors are RFC 9457 Problem Details (`application/problem+json`, schema `ProblemDetail`), produced
  centrally by `common.exception.GlobalExceptionHandler`. This includes unknown paths (`404`),
  unsupported methods (`405`) and unexpected failures (`500`, without internal details).
- All input is validated, and authorization and business rules are enforced on the server.

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
  "detail": "Method 'POST' is not supported.",
  "instance": "/api/v1/system/status"
}
```

## Planned (from the implementation plan)

These endpoints are added to the contract first, in the week they are implemented. The Week 2
operations, schemas, error codes and security schemes are already in `openapi.yaml` (contract
version 0.2.0) and marked `planned` (W2-02); the design rationale is in
[`docs/plans/week-02.md`](plans/week-02.md) §5.

| Method | Endpoint | Purpose | Week |
|---|---|---|---|
| GET | `/api/v1/auth/csrf` | Obtain the CSRF token cookie (SPA start-up) | 2 |
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
