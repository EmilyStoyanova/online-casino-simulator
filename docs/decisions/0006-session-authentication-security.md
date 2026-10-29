# ADR-0006: Session-based authentication and abuse protection

- **Status:** Proposed. Points 1–4 restate decisions approved on 2026-10-28; the ADR as a whole awaits approval.
- **Date:** 2026-10-29 (revised: simplified to standard Spring mechanisms)
- **Details:** `docs/plans/week-02.md` §7

## Context

The plan requires login and logout with protected session cookies, the roles PLAYER and ADMIN, and
safe handling of virtual-currency operations. The frontend is a single-page app that calls a JSON
API. It will be hosted on Cloudflare Pages (frontend) and Render (backend, free instances sleep
when idle).

The solution must be secure and explainable, and it must fit into one week of a 12-week schedule.
It should rely on Spring Security and Spring Session wherever they already solve the problem.

## Decision

1. **Server-side sessions with Spring Session JDBC** (PostgreSQL). No tokens in browser storage.
   Sessions survive restarts and sleeping instances. The session principal is Spring's `User`
   with the user id as username; Spring erases the password after authentication.
   `PRINCIPAL_NAME` = user id.
2. **Spring Security, deny by default.** Login, registration (with automatic sign-in) and logout
   are JSON controller endpoints described in `openapi.yaml`. Every sign-in changes the session id
   (`ChangeSessionIdAuthenticationStrategy`). Generic `401 INVALID_CREDENTIALS` for every login
   failure, and disabled accounts cannot log in.
3. **Passwords:** `DelegatingPasswordEncoder` with BCrypt cost 12. Policy: 12 characters minimum,
   72 UTF-8 bytes maximum (never truncated), not equal to the email or display name. A
   common-password denylist is added once its source and licence are verified.
4. **CSRF:** `csrf.spa()` (`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header) on all unsafe methods,
   including login and registration. The token is rotated on sign-in and cleared on logout by
   Spring. The SPA fetches a fresh token via `GET /api/v1/auth/csrf` at start-up and after these
   operations, and retries once on a CSRF error.
5. **Cookies:** the session cookie is `HttpOnly`, `SameSite=Lax`, `Path=/`, with no `Domain`. In
   production it is named `__Host-SESSION` and is `Secure`. All attributes come from configuration
   and are verified by an integration test with production-like properties.
6. **Session lifetime:** inactivity timeout of 30 min (Spring Session). Absolute lifetime of 12 h via
   a small filter that reads a timestamp stored in the already-loaded session, without an extra
   database query.
7. **Throttling:** fixed-window counters in PostgreSQL (one table, atomic upsert):
   - account + IP: 5 failures per 15 min;
   - IP: 50 failures per 15 min;
   - registration attempts per IP: 10 per hour.

   There is no account lockout, and the responses are identical for unknown accounts.
8. **Client IP:** `request.getRemoteAddr()` only. Forwarded headers are ignored by default
   (`forward-headers-strategy=none`). Production enables `native` only together with an explicit
   trusted-proxy list, following a deployment checklist (Week 12).
9. **Errors:** RFC 9457 Problem Details with stable codes for every error (including Spring Security
   401/403), no internal details, `Cache-Control: no-store`.

## Known limitations (accepted for Week 2)

- **Existing sessions are not revoked when a user is disabled or a role or password changes.** They
  end at the latest after 30 min of inactivity or 12 h. Week 2 has no feature that performs such
  changes. **Week 10 must** revoke all sessions of the affected user via Spring Session's
  `FindByIndexNameSessionRepository` whenever it disables users or changes roles. Manual database
  changes must also delete the user's `spring_session` rows (runbook in `docs/database.md`).
- **Distributed guessing of one account from many IPs** is limited only by the password policy and
  BCrypt cost.
- **The CSRF cookie uses no `__Host-` prefix.** This is acceptable for same-site routing without
  shared subdomains.

## Deferred (with trigger)

| Measure | Add when |
|---|---|
| Automatic session revocation on disable/role/password change | Week 10 (mandatory) |
| Device cookies (OWASP) against account-targeted slow-down | Evidence of distributed attacks on accounts |
| Per-request check of role/status | Only if Week 10 revocation proves insufficient |
| `__Host-` CSRF cookie | Routing with shared subdomains (option B) |
| Edge rate limiting | Week 12 deployment review |

## Alternatives considered

- **JWT in the browser:** revocation is hard and storage is exposed to XSS. Rejected.
- **In-memory sessions:** lost on every restart or sleep. Rejected.
- **Account lockout:** enables denial of service against known users. Rejected.
- **`security_version` trigger + per-request database check:** immediate revocation, but an extra
  query on every request and additional schema logic. Not needed while no feature changes roles or
  status. Replaced by Week 10 revocation through Spring Session.
- **Multi-scope throttling with device cookies:** stronger against distributed attacks, but more
  code and secret management than the MVP needs. Deferred.
- **Redis** for sessions and limits: extra infrastructure, excluded by ADR-0001.

## Consequences

- ✅ Uses only Spring Security and Spring Session features plus one small filter and one throttle table.
- ✅ No extra database work per request beyond loading the session.
- ⚠️ Revocation of existing sessions becomes a hard requirement for Week 10.
- ⚠️ Correct production behaviour depends on same-site routing and the trusted-proxy checklist
  (Week 12).
