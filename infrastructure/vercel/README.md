# Vercel: frontend staging deployment

The React + Vite frontend is served by Vercel as a static build. A Vercel rewrite forwards `/api/*`
to the backend on Render ([render/README.md](../render/README.md)), so the browser sees a single
origin:

```
Browser ──HTTPS──▶ https://<project>.vercel.app
                     ├─ /assets/*, /index.html   static files of frontend/dist
                     ├─ /api/*                   rewrite ──HTTPS──▶ https://online-casino-simulator.onrender.com/api/*
                     │                                              (Cloudflare → Render proxy → Spring Boot → Neon staging)
                     └─ every other path         /index.html (React Router)
```

This page is the runbook: what the repository configures, the exact dashboard settings, the one
backend setting the rewrite needs, and how to verify the result. Nothing here deploys
automatically. The final hosting decision is still ADR-0007 (Week 12); this setup is for staging.

## What the repository provides

| File | Purpose |
|---|---|
| [`frontend/vercel.json`](../../frontend/vercel.json) | Build (`npm ci`, `npm run build`, `dist`), the two rewrites, no CDN caching for `/api`, security headers for the pages, long caching for content-hashed `/assets` |
| [`frontend/src/vercelConfig.test.ts`](../../frontend/src/vercelConfig.test.ts) | Routing and header rules: API before the SPA fallback, one fixed HTTPS backend, no CORS headers, no variables |
| [`VercelProxyIT`](../../backend/src/test/java/com/casino/auth/config/VercelProxyIT.java) | Backend behind the rewrite: CSRF, sign-in, profile and sign-out from the configured origin; other origins rejected |

`vercel.json` is the source of truth for the build: its values override the dashboard fields.

### Rewrites

Order matters: Vercel serves built files first, then applies the first matching rewrite.

| # | Source | Destination | Why |
|---|---|---|---|
| 1 | `/api/(.*)` | `https://online-casino-simulator.onrender.com/api/$1` | Same-origin API: first-party cookies, no CORS in the browser |
| 2 | `/((?!api(?:/\|$)\|assets/).*)` | `/index.html` | Client-side routes (`/login`, `/profile`, unknown pages). `/api…` and missing `/assets/*` files are excluded, so an API or script request never gets HTML |

The sources are plain regular expressions (no named parameters), so the unit test evaluates exactly
what Vercel evaluates.

### Headers

| Paths | Header | Value and reason |
|---|---|---|
| `/api/*` | `x-vercel-enable-rewrite-caching` | `0`: the CDN never caches API answers. They carry `Set-Cookie` and user data. The backend also sends `Cache-Control: no-store`. |
| pages | `Content-Security-Policy` | `'self'` for scripts, styles, connections, fonts and images (plus `data:` images); no `unsafe-inline`/`unsafe-eval`; `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`, `upgrade-insecure-requests`. The production build has no inline scripts or styles (checked in Chrome: no violations). |
| pages | `Referrer-Policy` | `same-origin`: no referrer to other sites. Not `no-referrer`: the Fetch standard then sends `Origin: null` on POSTs, which the backend rejects. |
| pages | `X-Content-Type-Options`, `X-Frame-Options`, `Cross-Origin-Opener-Policy`, `Permissions-Policy` | `nosniff`, `DENY`, `same-origin`, camera/microphone/geolocation/payment/usb off |
| `/assets/*` | `Cache-Control` | `public, max-age=31536000, immutable`: file names contain a content hash |

Vercel adds HSTS to its own domains. API responses also carry the backend's HSTS header.

## Vercel dashboard settings

Vercel Dashboard → **Add New… → Project** → **Import Git Repository** → GitHub
`EmilyStoyanova/online-casino-simulator` (grant access to this repository only).

| Setting (Configure Project) | Value |
|---|---|
| Project Name | for example `online-casino-simulator`. It decides the production domain `https://<name>.vercel.app` (Vercel adds a suffix if the name is taken). |
| Framework Preset | **Vite** |
| Root Directory | **`frontend`** (Edit → select the folder) |
| Build Command | leave the default; `vercel.json` sets `npm run build` |
| Output Directory | leave the default; `vercel.json` sets `dist` |
| Install Command | leave the default; `vercel.json` sets `npm ci` |
| Environment Variables | **none** |

After the project exists:

| Setting | Value |
|---|---|
| Settings → Build and Deployment → **Node.js Version** | **24.x** (matches `frontend/.nvmrc` and CI; `engines` in `package.json` only sets a minimum) |
| Settings → Git → **Production Branch** | `main` |
| Settings → Deployment Protection → **Vercel Authentication** | **Standard Protection** (the default): preview deployments need a Vercel login, production is public |
| Settings → Domains | note the exact production domain, for example `https://online-casino-simulator.vercel.app` |

**Environment variables: none.** In particular:
- **Do not set `VITE_API_BASE_URL`.** Empty means same origin through the rewrite. A Render URL there
  would make every API call cross-site: the browser would no longer send the session cookie, and
  JavaScript could not read `XSRF-TOKEN`, so sign-in would fail.
- `VITE_DEV_PROXY_TARGET` is for the local dev server only.
- Every `VITE_*` value is compiled into the public JavaScript bundle. Never put secrets there. The
  frontend needs no secrets at all: no database, Neon or Render values belong in Vercel.

**Deployments.** Importing the repository deploys it immediately. After that, Vercel builds
`main` as production and every pushed branch as a protected preview. To deploy only on purpose,
use Settings → Git → **Ignored Build Step**, or disconnect Git and deploy from the dashboard.

## Backend setting required: `APP_CORS_ALLOWED_ORIGINS`

**Sign-in and sign-out fail with `403 Invalid CORS request` until this is set.**

The browser sends `Origin: https://<project>.vercel.app` on every POST. Vercel forwards it unchanged
and sets `Host` to Render's host name. Spring therefore treats sign-in and sign-out as cross-origin
requests and allows them only for an exact origin in its list. This is the same situation as with
the Vite dev server, where `http://localhost:5173` is allowed. Same-origin GETs carry no `Origin` and
work without the setting.

Render → service → **Environment** → add:

| Variable | Value |
|---|---|
| `APP_CORS_ALLOWED_ORIGINS` | the exact production origin: `https://` plus the domain, no path, no trailing slash, for example `https://online-casino-simulator.vercel.app` |

Then **Save and deploy** (no rebuild needed).

Rules:
- **Never** `*`, a pattern, `localhost`, or `http://`. The backend allows credentials, so only exact
  origins are accepted. Spring itself refuses `*` together with credentials.
- **Preview deployments** have other origins (`<project>-git-<branch>-<team>.vercel.app`). Do not
  add them: they can show pages and call GET endpoints, but sign-in is rejected there by design. If
  a stable preview is needed, add exactly one fixed alias, never a wildcard.
- A later custom domain replaces the value. List both only while switching.

This does not weaken anything:
- CORS only decides whether a browser page from that origin may send the request.
- CSRF (`X-XSRF-TOKEN` must match the cookie), the session check and throttling still apply to every
  request.

## Cookies, Host and forwarded headers behind the rewrite

| Aspect | Behaviour | Verified by |
|---|---|---|
| Cookie domain | The backend sets no `Domain`, so each cookie belongs to the host the browser asked: the Vercel domain. The browser never sees `onrender.com`. | `VercelProxyIT`, verification below |
| `__Host-SESSION` | `Secure; HttpOnly; SameSite=Lax; Path=/`, no `Domain`. Valid because the page is HTTPS and the cookie is host-only. First-party, so `SameSite=Lax` applies normally. | `VercelProxyIT`, `RenderProxyIT` |
| `XSRF-TOKEN` | `Secure; Path=/`, readable by the SPA on the same origin. Deleted after sign-in, sign-out and registration; the SPA fetches a new one. | `VercelProxyIT`, `CsrfIT` |
| `Host` | Render's host (`online-casino-simulator.onrender.com`). The backend builds no absolute URLs or redirects from it. | — |
| `Origin` | The browser's origin, forwarded unchanged; checked against `APP_CORS_ALLOWED_ORIGINS`. | `VercelProxyIT`, Chrome check |
| `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Host`, `X-Forwarded-Proto` | Vercel adds them. The profile `render` ignores all of them (`forward-headers-strategy=none`), so no client can forge an address or HTTPS. | `VercelProxyIT`, `RenderProxyIT` |
| HSTS | Vercel's own on pages; the backend's on `/api` responses. Both apply to the Vercel host only. | verification below |

## Render Free cold starts and Vercel's proxy timeout

- **Vercel** waits at most **120 s** for an external rewrite target. After that it answers
  `502 ROUTER_EXTERNAL_TARGET_ERROR`
  ([Vercel docs](https://vercel.com/docs/errors/ROUTER_EXTERNAL_TARGET_ERROR)).
- **Render Free** spins down after 15 minutes without traffic, and Render says starting up again
  takes about a minute ([Render docs](https://render.com/docs/free)). Measured locally with the
  Free instance's limits (0.1 CPU), the backend takes **~112–123 s** until it is ready
  ([measurements](../render/README.md#free-plan-limits-and-measurements)).
- **Consequence:** the first API request after a spin-down can exceed 120 s and get a 502 from
  Vercel. Requests after that are normal. Login takes 5–12 s and registration ~16 s on the Free
  instance, well under the limit.
- **What the frontend does:**
  - The page itself is served by Vercel, so it always loads.
  - The platform status card wakes the backend on the home page.
  - A 502/503/504 without Problem Details, or a non-JSON 2xx answer such as a loading page or a
    misrouted `index.html`, shows: "The server is starting up or temporarily unavailable. This can
    take about a minute; please try again shortly."
  - Nothing is retried automatically. A POST that timed out at the proxy may still have been
    processed by the backend.
- **Not done on purpose:**
  - No keep-alive pinging. It would use the workspace's 750 free instance hours and needs a
    separate decision.
  - No lower BCrypt cost.
- **Removing the risk:** a paid Render instance does not spin down and starts in seconds. That is a
  separate decision with costs.

## Deploy in the browser

1. **Prerequisite:** this branch is merged into `main` with green CI.
2. Create the project with the [dashboard settings](#vercel-dashboard-settings). The first
   production deployment starts automatically.
3. Note the production domain (Settings → Domains).
4. On Render, set [`APP_CORS_ALLOWED_ORIGINS`](#backend-setting-required-app_cors_allowed_origins)
   to that exact origin and deploy the backend.
5. Run the [verification](#verify-after-a-deploy).

## Verify after a deploy

Replace `<project>` with the production domain's name. These commands print no secrets. If the
backend was asleep, wait for the first command (up to ~2 min); a `502` here means the cold start
exceeded Vercel's timeout, so run it again.

```bash
F=https://<project>.vercel.app
curl -s --max-time 150 $F/api/v1/system/status; echo                   # JSON: "status":"UP", "database":{"status":"UP","schemaVersion":"5"}
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' $F/profile    # 200 text/html (SPA fallback)
curl -s -o /dev/null -w '%{http_code}\n' $F/assets/missing.js           # 404 (no HTML for missing files)
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' $F/api/v1/unknown   # 401 application/problem+json from the backend, not the SPA
```

**Page headers:**

```bash
curl -s -D - -o /dev/null $F/ | tr -d '\r' \
  | grep -i -E '^(content-security-policy|referrer-policy|x-frame-options|strict-transport-security):'
```

Expected: the CSP from `vercel.json`, `referrer-policy: same-origin`, `x-frame-options: DENY`, and
Vercel's `strict-transport-security`.

**Cookies through the proxy** (values masked):

```bash
curl -s -D - -o /dev/null $F/api/v1/auth/csrf | tr -d '\r' \
  | grep -i -E '^(strict-transport-security|set-cookie|cache-control|x-vercel-cache):' \
  | sed -E 's/(XSRF-TOKEN|SESSION)=[^;]*/\1=<value>/'
```

Expected:
- `set-cookie: XSRF-TOKEN=<value>; Path=/; Secure`, with no `Domain`;
- the backend's `strict-transport-security: max-age=31536000 ; includeSubDomains`;
- `cache-control: no-cache, no-store, …`;
- `x-vercel-cache` is not `HIT`.

**Origin check.** The first request proves that `APP_CORS_ALLOWED_ORIGINS` is set: the CORS check
passes, then CSRF rejects the request because it has no token. The second request is from a foreign
origin and is rejected by CORS:

```bash
curl -s -X POST -H "Origin: $F" -H 'Content-Type: application/json' -d '{}' $F/api/v1/auth/login; echo
# {"…","status":403,"code":"CSRF_TOKEN_INVALID"}   (if the body is "Invalid CORS request", the variable is missing or differs)
curl -s -X POST -H 'Origin: https://evil.example' -H 'Content-Type: application/json' -d '{}' $F/api/v1/auth/login; echo
# Invalid CORS request
```

**Registration is closed** (through the proxy, with a token from a temporary cookie jar and an
empty body, so it can never create an account):

```bash
J=$(mktemp)
curl -s -c "$J" -o /dev/null $F/api/v1/auth/csrf
curl -s -b "$J" -H "Origin: $F" -H "X-XSRF-TOKEN: $(awk '$6=="XSRF-TOKEN"{print $7}' "$J")" \
  -H 'Content-Type: application/json' -d '{}' $F/api/v1/auth/register; echo
rm -f "$J"
```

Expected: `403` with `"code":"REGISTRATION_DISABLED"`.

**In the browser** (a private window, DevTools open):
1. `$F/`: the platform status shows Backend UP and Database UP. The Console shows no CSP errors.
2. `$F/register`: fill in the form and submit. The form is replaced by **"Registration is closed"**
   with a **Go to sign in** button.
3. Reload `$F/profile` directly: the sign-in page appears (SPA fallback plus the session check).
4. With an existing staging account only: sign in.
   - DevTools → Application → Cookies → the Vercel domain shows `__Host-SESSION` with HttpOnly ✓,
     Secure ✓, SameSite Lax, and `XSRF-TOKEN` with Secure ✓.
   - Network → the `login` request has `Origin` set to the Vercel domain and status 200.
   - Sign out: `__Host-SESSION` disappears, and `/profile` leads to the sign-in page again.

   While registration is closed, staging may have no account. The flow is then covered by
   `VercelProxyIT`.

On a **preview deployment**, steps 1–3 behave the same. Sign-in fails with "Something went wrong",
because the preview's origin is not allowed (the response is `403 Invalid CORS request`). That is
expected.

## Remaining risks and limits

- **Client address for throttling.** All users share one address: Render's proxy, behind Vercel's
  egress. Throttling cannot be bypassed with forged headers, but:
  - 50 failed sign-ins from anyone block sign-in for everyone for 15 minutes;
  - 5 failed sign-ins for one account block it for everyone.

  Acceptable for a closed staging; not for public users. The safe fix:
  - Vercel adds a secret header to every rewrite
    ([Vercel docs](https://vercel.com/docs/routing/rewrites#restricting-your-origin-to-vercel-traffic));
  - the backend trusts Vercel's `x-real-ip` only when that header matches.

  This needs a backend change and a secret in both dashboards. It is not part of this setup.
- **Cold starts** can exceed Vercel's 120 s ([above](#render-free-cold-starts-and-vercels-proxy-timeout)).
- **Direct backend access.** `https://online-casino-simulator.onrender.com` stays reachable without
  Vercel, with the same CSRF, session and throttling rules. Browsers cannot use it from another
  origin (CORS), and cookies set there do not apply to the Vercel domain.
- **Vercel toolbar on previews.** The CSP blocks the toolbar scripts (`vercel.live`) on preview
  deployments; the console shows CSP errors there. Production is unaffected.
- **Custom domain later.** The backend's HSTS has `includeSubDomains`. On an apex domain it would
  also apply to every subdomain. Review before adding a custom domain, and update
  `APP_CORS_ALLOWED_ORIGINS`.

## Rollback

- **Frontend:** Vercel → Deployments → an earlier deployment → **Promote to Production**, or delete
  the project.
- **Backend:** remove `APP_CORS_ALLOWED_ORIGINS` on Render and deploy. The backend is then back to
  "no browser origin".

Nothing here touches the database.
