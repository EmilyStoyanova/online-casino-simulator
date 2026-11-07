# Render: backend staging deployment

The Spring Boot backend runs as a Docker web service on Render. It uses the Neon `staging` branch
(see [docs/database.md → Neon](../../docs/database.md#neon)). The frontend is not deployed yet.

This page is the runbook: what is configured where, the browser steps, and how to verify the
result. Nothing here deploys automatically.

## What the repository provides

| File | Purpose |
|---|---|
| [`backend/Dockerfile`](../../backend/Dockerfile) | Multi-stage image: Maven build on Temurin 21 JDK, runtime on Temurin 21 JRE (Alpine), non-root user `10001`, base images pinned by version and digest |
| [`backend/.dockerignore`](../../backend/.dockerignore) | Keeps `.env*`, keys, `target/` and IDE files out of the build context |
| [`application-render.yml`](../../backend/src/main/resources/application-render.yml) | Profile `render`: `PORT`, trusted proxy, `__Host-SESSION` + `Secure`, no default CORS origin, registration closed unless `APP_REGISTRATION_ENABLED=true`, Swagger UI off, 20 s shutdown phase |
| [`application-neon.yml`](../../backend/src/main/resources/application-neon.yml) | Profile `neon`: verified TLS to the Neon direct endpoint |

The image contains **no configuration values**. Profiles, database and secrets come from Render's
environment variables at runtime. The Dockerfile declares no build arguments, so Render's
environment variables (which it also passes to `docker build`) are never written into the image.

A `render.yaml` Blueprint is deliberately not used. One service is quicker to set up and review in
the dashboard, and a Blueprint could not hold the secrets anyway (`sync: false`). Revisit when there
are several services (Week 12).

## Render settings

| Setting | Value |
|---|---|
| Service type | **Web Service** |
| Language (runtime) | **Docker** |
| Repository | `EmilyStoyanova/online-casino-simulator` (GitHub) |
| Branch | `main`, after this configuration is merged with green CI |
| Region | **Frankfurt (EU Central)**, the same region as Neon (AWS Europe Central 1) |
| Root Directory | `backend` (only changes below it can trigger deploys) |
| Dockerfile Path | `./Dockerfile` (relative to the root directory) |
| Docker Build Context Directory | `.` (that is, `backend/`) |
| Docker Command | empty (the image's `java -jar app.jar`) |
| Instance type | **Free** (limits below) |
| Health Check Path | `/actuator/health/readiness` |
| Auto-Deploy | **Off** until the staging configuration is reviewed; later "After CI Checks Pass" |
| Pre-deploy command | none: Flyway migrates at start-up, and pre-deploy commands are not available on Free |

## Environment variables

Enter them only in Render: **Service → Environment → Add Environment Variable**. Type or paste
each value directly from its source, never through a chat, a ticket, a commit or a shell command.

| Variable | Value | Secret |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `neon,render` | no |
| `NEON_DB_HOST` | Direct endpoint of the **staging** branch (`ep-….eu-central-1.aws.neon.tech`, **no** `-pooler`) | no, but do not publish it |
| `NEON_DB_NAME` | `neondb` | no |
| `NEON_DB_USERNAME` | The staging role (for example `neondb_owner`) | no |
| `NEON_DB_PASSWORD` | That role's password | **yes** |
| `APP_SECURITY_THROTTLE_SECRET` | A **new** value, different from every local one (see below) | **yes** |
| `APP_REGISTRATION_ENABLED` | `false` until the deployment is verified ([registrations](#registrations-on-a-public-url)) | no |

Do not set the following:
- `PORT`: Render provides it (10000).
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: the local database settings.
- `APP_CORS_ALLOWED_ORIGINS`: no browser origin until a frontend exists.
- `APP_BOOTSTRAP_ADMIN_*`: no administrator on staging unless decided separately.

Optional, only when needed:
- `JDK_JAVA_OPTIONS`: JVM flags (memory notes below).
- `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`: only if the proxy check below shows that Render's
  load balancer is not in `10.0.0.0/8`.

**Generate the throttling secret without displaying it.** In Git Bash this copies the value
straight to the clipboard; paste it into Render, then copy something else:

```bash
openssl rand -base64 32 | tr -d '\n' | clip
```

**Use the staging branch only.** Copy the four `NEON_DB_*` values from Neon Console → branch
`staging` → **Connect** (Connection pooling **off**). After the first start, check in the log that
Flyway reports the schema as up to date: staging is already at v5. If it reports migrations being
applied, stop the service: it is pointing at a different database.

## Deploy in the browser

1. **Prerequisite:** this configuration is merged into `main` with green CI. Render builds from
   GitHub, never from the local working tree.
2. Render Dashboard → **New → Web Service** → connect GitHub (only this repository) → select
   `online-casino-simulator`.
3. Fill in the [settings](#render-settings): name (for example `casino-backend-staging`, which
   becomes the URL `https://casino-backend-staging.onrender.com`), language Docker, branch, region,
   Root Directory, instance type Free.
4. **Environment Variables:** add the seven [variables](#environment-variables), including
   `APP_REGISTRATION_ENABLED=false`.
5. **Advanced:**
   - Health Check Path `/actuator/health/readiness`;
   - Dockerfile Path `./Dockerfile`;
   - Docker Build Context Directory `.`;
   - Auto-Deploy **Off**.
6. **Create Web Service.** This starts the first build and deploy. The build takes a few minutes (about 2.5 min locally),
   and starting on a Free instance takes about 2 min more.
7. Follow **Logs**. Expected:
   - `The following 2 profiles are active: "neon", "render"`;
   - `Registration is closed (APP_REGISTRATION_ENABLED=false): POST /api/v1/auth/register answers 403`;
   - `Schema "public" is up to date. No migration necessary.`;
   - `Tomcat started on port 10000`;
   - `Started CasinoSimulatorApplication`.
8. Run the [verification](#verify-after-a-deploy).

## Verify after a deploy

Replace `<service>` with the service name. These commands print no secrets.

```bash
B=https://<service>.onrender.com
curl -s --max-time 300 $B/actuator/health/readiness   # {"status":"UP"}; up to ~2 min after a spin-down
curl -s $B/api/v1/system/status                       # "database":{"status":"UP","schemaVersion":"5"}
```

**Proxy and HTTPS handling.** This is the check that matters most:

```bash
curl -s -D - -o /dev/null $B/api/v1/auth/csrf | grep -i -E 'strict-transport-security|set-cookie' | sed -E 's/=[^;]*/=<value>/'
```

Expected: a `strict-transport-security` header, and `set-cookie: XSRF-TOKEN=<value>; Path=/; Secure`.
Both appear only when the application trusts Render's load balancer and sees the original HTTPS
request.

If both are missing, the load balancer's address is not in `10.0.0.0/8`:
- Security is not weakened: forwarded headers are simply ignored, and the session cookie is still
  `__Host-SESSION; Secure`.
- HSTS is missing, and every client shares the load balancer's address for throttling.
- To see the real address, add these variables (Save and deploy), make one request, and read the
  `access peer=…` line in the log:

  ```text
  SERVER_TOMCAT_ACCESSLOG_ENABLED=true
  SERVER_TOMCAT_ACCESSLOG_DIRECTORY=/dev
  SERVER_TOMCAT_ACCESSLOG_PREFIX=stdout
  SERVER_TOMCAT_ACCESSLOG_SUFFIX=
  SERVER_TOMCAT_ACCESSLOG_FILE_DATE_FORMAT=
  SERVER_TOMCAT_ACCESSLOG_BUFFERED=false
  SERVER_TOMCAT_ACCESSLOG_PATTERN=access peer=%h xff="%{X-Forwarded-For}i" proto="%{X-Forwarded-Proto}i" %r %s
  ```

  Then set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a regular expression for **only** that
  range, and remove the access-log variables again: the log would contain client IP addresses.

**Security regression checks** (each must give the shown status):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X OPTIONS -H 'Origin: https://evil.example' \
  -H 'Access-Control-Request-Method: POST' $B/api/v1/auth/login                    # 403 (CORS)
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
  -d '{}' $B/api/v1/auth/login                                                     # 403 (CSRF)
curl -s -o /dev/null -w '%{http_code}\n' $B/swagger-ui.html                        # 404
curl -s -o /dev/null -w '%{http_code}\n' $B/actuator/env                           # 401
curl -s -o /dev/null -w '%{http_code}\n' http://<service>.onrender.com/actuator/health   # 3xx redirect to HTTPS
```

**Registration is closed.** The request carries a CSRF token from a temporary cookie jar and an
empty body, so it can never create an account:

```bash
J=$(mktemp)
curl -s -c "$J" -o /dev/null $B/api/v1/auth/csrf
curl -s -b "$J" -H "X-XSRF-TOKEN: $(awk '$6=="XSRF-TOKEN"{print $7}' "$J")" \
  -H 'Content-Type: application/json' -d '{}' $B/api/v1/auth/register; echo
rm -f "$J"
```

Expected while closed: `403` with `"code":"REGISTRATION_DISABLED"`. Once registration has been
opened, the same request gets `400 VALIDATION_FAILED`: the empty body is rejected and no account is
created.

## Free plan: limits and measurements

Render's Free web service:
- spins down after **15 minutes** without inbound traffic;
- may be restarted at any time;
- has no shell access and an ephemeral filesystem;
- has **750 instance hours per workspace and month**.

Sessions and all data are in Neon, so restarts and spin-downs lose nothing.

Measured locally with the same image, PostgreSQL 18.6 and Docker limits of **512 MB RAM and
0.1 CPU** (the Free instance's size as shown in the dashboard). Render's real CPU share may differ:

| Measurement | Result |
|---|---|
| Start until ready (also every cold start after a spin-down) | **~112–123 s** |
| Memory after start / peak under light load | 238 MiB / 258 MiB of 512 MiB, no OOM |
| Registration (BCrypt cost 12) | ~16 s |
| Login | 5–12 s |
| 40 parallel authenticated requests | 140 s in total |
| Graceful shutdown (SIGTERM) | 2 s |
| Start with 1 CPU, for comparison | ~9 s |

What this means:
- **Cold starts** take about 2 minutes. Browsers see Render's loading page; API clients need a long
  timeout.
- **The Free instance is enough for smoke tests and demos, not for real users.** BCrypt cost 12 is
  part of the security policy (ADR-0006) and is not lowered to make it faster.
- Under parallel load, the readiness check (5 s timeout) can fail. After 60 s of failures Render
  restarts the instance.
- For anything beyond staging smoke tests, use a paid instance with more CPU. That is a separate
  decision with costs.

**Memory settings** (`JDK_JAVA_OPTIONS` in the Dockerfile, which can be overridden in Render):
- heap at most 60 % of the container limit;
- Serial GC;
- C1 compiler only;
- 512 KiB thread stacks;
- capped metaspace (192 MiB) and code cache (64 MiB);
- exit on `OutOfMemoryError`, so that Render restarts the instance.

Tomcat runs at most 50 request threads, and the Neon pool has 5 connections.

## Security configuration (public HTTPS)

| Concern | Configuration |
|---|---|
| TLS | Render terminates HTTPS and redirects HTTP. Spring sends HSTS once the proxy is trusted (see the verification above). |
| Reverse proxy | `forward-headers-strategy=native`, trusted peers only `10.0.0.0/8`. Tomcat takes the right-most `X-Forwarded-For` entry not added by a trusted proxy, so a client-supplied entry is ignored. This was tested: a spoofed left entry did not escape the throttle. |
| Client IP for throttling | Render appends the Cloudflare edge address, so the client IP the app sees is a **Cloudflare edge address**: users behind the same edge share the IP-based limits. Per-user IPs would need Cloudflare's ranges as trusted proxies after verifying the chain (Week 12). |
| Session cookie | `__Host-SESSION`, `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain` (tested in the container). Sessions in Neon survive restarts (tested). |
| CSRF | Unchanged (`csrf.spa()`, ADR-0006). The `XSRF-TOKEN` cookie becomes `Secure` behind the trusted proxy. |
| CORS | No origin allowed by default on Render, and `localhost` is rejected (tested). Set `APP_CORS_ALLOWED_ORIGINS` to the exact frontend origin when one exists. |
| Errors | Problem Details without stack traces or internal details (`server.error.include-stacktrace=never`). The log has no passwords, emails or session ids (checked in the test run). |
| Actuator | Only `health` (status only, no details) and `info` (empty) are exposed; everything else is `401`. |
| API docs | Swagger UI and `/v3/api-docs` off; the contract `/openapi.yaml` stays public. |
| Registration | Closed on Render by default (`APP_REGISTRATION_ENABLED`, [below](#registrations-on-a-public-url)): `403 REGISTRATION_DISABLED`, after the CSRF check and before any throttling or body parsing. |
| Start-up validation | Missing throttling secret, missing Neon values or a forwarded-header strategy of `framework`: the backend refuses to start (tested). |

### Registrations on a public URL

New accounts are controlled by `APP_REGISTRATION_ENABLED`. It is server configuration only: no
request, header or frontend can change it.

- **Closed** (`false`, and the default of the profile `render` when the variable is missing): every
  `POST /api/v1/auth/register` gets `403 REGISTRATION_DISABLED`, still after the CSRF check. Nothing
  is read, counted or stored. Login, logout, sessions and login throttling are unchanged.
- **Open** (`true`, the local default): registration as before, throttled at 10 attempts per hour per
  client address (here a Cloudflare edge address).

**Open registration after the deployment has been verified.** The value is read at start-up, so a
change needs a deploy, but no rebuild:
1. Run every [verification](#verify-after-a-deploy) step with registration closed.
2. Render → service → **Environment** → `APP_REGISTRATION_ENABLED` → change the value to `true` →
   **Save and deploy**.
3. Wait for the deploy to finish. The log no longer shows `Registration is closed`.
4. Check: the [registration probe](#verify-after-a-deploy) now gets `400 VALIDATION_FAILED`.

**Close it again** the same way with `false`. Existing accounts and sessions stay valid.

Until registration is opened on purpose, do not share the URL or invite real users.

## Same-origin `/api` proxy for the future frontend (Vercel)

The proposal is to serve the frontend on Vercel and route `/api/*` to this backend with a Vercel
rewrite, so the browser sees a single origin. The architecture currently names Cloudflare Pages;
the final choice belongs to the deployment decision (ADR-0007, Week 12). Feasibility:

- **Works:**
  - external rewrites proxy requests and responses, including cookies, so `__Host-SESSION` and the
    CSRF cookie stay first-party with `SameSite=Lax`;
  - Vercel honours the backend's `Cache-Control: no-store`, so API responses are not cached.
- **Must be configured:** `APP_CORS_ALLOWED_ORIGINS` = the exact Vercel origin. Browsers send
  `Origin` on unsafe requests, as with the Vite proxy in development.
- **Limitation, client IP:** requests reach Render from Vercel, so throttling would see Vercel or
  Cloudflare addresses for all users. Vercel forwards the visitor IP (`x-real-ip`, `x-forwarded-for`)
  and can add a secret header to prove that a request came through it. Using that safely needs a
  small backend change. Decide this before the frontend goes public.
- **Risk, cold start:** a rewrite waiting about 2 minutes for a spun-down Free instance may time out
  on Vercel's side; this has not been verified. A paid instance does not spin down.
