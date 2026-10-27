# API

- Base path: `/api/v1` (administrative endpoints: `/api/v1/admin/**`)
- Format: JSON; errors as RFC 9457 Problem Details (`application/problem+json`)
- Live specification: `/v3/api-docs` · interactive UI: `/swagger-ui.html`
- All input is validated and all authorization and business rules are enforced on the server.

## Implemented

### `GET /api/v1/system/status`

Reports whether the backend is running and can reach its database. Public, no authentication.

| Status | Meaning |
|---|---|
| `200 OK` | Platform up |
| `503 Service Unavailable` | Database unreachable (body still describes the status) |

```json
{
  "application": "online-casino-simulator",
  "version": "0.1.0-SNAPSHOT",
  "status": "UP",
  "database": { "status": "UP", "schemaVersion": "1" },
  "timestamp": "2026-10-27T18:23:39.093Z"
}
```

### `GET /actuator/health`

Spring Boot health endpoint for infrastructure probes (`/actuator/health/liveness`,
`/actuator/health/readiness`). Only `health` and `info` actuator endpoints are exposed.

## Planned (from the implementation plan)

| Method | Endpoint | Purpose | Week |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Registration | 2 |
| POST | `/api/v1/auth/login` | Login (session cookie) | 2 |
| POST | `/api/v1/auth/logout` | Logout | 2 |
| GET | `/api/v1/users/me` | Current user profile | 2 |
| PATCH | `/api/v1/users/me` | Update profile / interests | 2 |
| GET | `/api/v1/wallet` | Current virtual balance | 3 |
| GET | `/api/v1/wallet/transactions` | Transaction history | 3 |
| POST | `/api/v1/games/roulette/spins` | Place bets and play a round (idempotency key) | 4 |
| GET | `/api/v1/games/roulette/history` | Roulette history | 6 |
| GET | `/api/v1/learning/modules` | Educational modules | 7 |
| POST | `/api/v1/learning/modules/{id}/attempts` | Submit quiz answers | 8 |
| POST | `/api/v1/learning/modules/{id}/rewards` | Claim an eligible reward | 8 |
| GET | `/api/v1/dashboard` | Player statistics | 9 |
| PUT | `/api/v1/play-limits` | Configure play limits | 9 |
