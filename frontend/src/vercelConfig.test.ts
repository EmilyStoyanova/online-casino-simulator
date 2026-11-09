import { describe, expect, it } from 'vitest'
import vercelJson from '../vercel.json?raw'

/**
 * The Vercel deployment (`vercel.json`): the SPA and its API on one origin. `/api/*` is rewritten to the
 * backend on Render, everything else that is not a built file to `index.html` (React Router).
 *
 * The sources are plain regular expressions without named parameters, so they mean the same here as in
 * Vercel's path-to-regexp (which matches the whole path, case-insensitively, with an optional trailing
 * slash). Vercel serves built files before it applies rewrites, and the first matching rewrite wins.
 */
interface Rule {
  source: string
  destination: string
}
interface HeaderRule {
  source: string
  headers: { key: string; value: string }[]
}
interface VercelConfig {
  rewrites: Rule[]
  headers: HeaderRule[]
  [key: string]: unknown
}

const config = JSON.parse(vercelJson) as VercelConfig
const BACKEND = 'https://online-casino-simulator.onrender.com'
/** Files of the production build (`dist`), served before any rewrite. */
const BUILT_FILES = new Set(['/index.html', '/assets/index-a1b2c3.js', '/assets/index-d4e5f6.css'])

function matcher(source: string): RegExp {
  return new RegExp(`^${source}/?$`, 'i')
}

/** What Vercel serves for `path`: a built file, a rewrite destination, or `undefined` (404). */
function route(path: string): string | undefined {
  if (BUILT_FILES.has(path)) return path
  for (const { source, destination } of config.rewrites) {
    const match = matcher(source).exec(path)
    if (match) {
      return destination.replace(/\$(\d+)/g, (_, group: string) => match[Number(group)] ?? '')
    }
  }
  return undefined
}

function headersFor(path: string): Record<string, string> {
  const headers: Record<string, string> = {}
  for (const rule of config.headers) {
    if (matcher(rule.source).test(path)) {
      for (const { key, value } of rule.headers) headers[key.toLowerCase()] = value
    }
  }
  return headers
}

describe('vercel.json rewrites', () => {
  it.each([
    '/api/v1/auth/csrf',
    '/api/v1/auth/login',
    '/api/v1/auth/logout',
    '/api/v1/users/me',
    '/api/v1/system/status',
    '/api/v1/interests',
  ])('sends %s to the same path on the backend', (path) => {
    expect(route(path)).toBe(`${BACKEND}${path}`)
  })

  it('serves the SPA for every client-side route, including unknown ones (React Router shows 404)', () => {
    for (const path of ['/', '/login', '/register', '/profile', '/profile/', '/no/such/page', '/apiary', '/assets']) {
      expect(route(path), path).toBe('/index.html')
    }
  })

  it('never serves the SPA for API paths or missing build files', () => {
    // index.html as an API answer or a script would hide the real error.
    for (const path of ['/api', '/api/', '/assets/missing.js']) {
      expect(route(path), path).not.toBe('/index.html')
    }
    expect(route('/api')).toBeUndefined()
    expect(route('/assets/missing.js')).toBeUndefined()
  })

  it('serves built files directly and checks the API rule before the SPA fallback', () => {
    expect(route('/assets/index-a1b2c3.js')).toBe('/assets/index-a1b2c3.js')
    expect(config.rewrites.map((rule) => rule.destination)).toEqual([`${BACKEND}/api/$1`, '/index.html'])
  })

  it('proxies to one fixed HTTPS backend and only under /api', () => {
    const external = config.rewrites.filter((rule) => /^[a-z]+:/i.test(rule.destination))
    expect(external).toHaveLength(1)
    expect(new URL(external[0]?.destination.replace('$1', 'x') ?? '').origin).toBe(BACKEND)
    expect(external[0]?.destination.startsWith(`${BACKEND}/api/`)).toBe(true)
  })
})

describe('vercel.json headers', () => {
  it('never lets the CDN cache API answers (they set session and CSRF cookies)', () => {
    expect(headersFor('/api/v1/auth/login')).toEqual({ 'x-vercel-enable-rewrite-caching': '0' })
  })

  it('adds no CORS headers: the browser only ever talks to its own origin', () => {
    for (const path of ['/', '/api/v1/auth/login']) {
      expect(Object.keys(headersFor(path)).filter((key) => key.startsWith('access-control-'))).toEqual([])
    }
  })

  it('protects the pages with a strict Content-Security-Policy', () => {
    const csp = headersFor('/login')['content-security-policy'] ?? ''
    const directives = Object.fromEntries(
      csp.split(';').map((directive) => {
        const [name = '', ...values] = directive.trim().split(/\s+/)
        return [name, values.join(' ')]
      }),
    )

    expect(directives).toMatchObject({
      'default-src': "'self'",
      'script-src': "'self'",
      'connect-src': "'self'",
      'object-src': "'none'",
      'base-uri': "'none'",
      'frame-ancestors': "'none'",
    })
    expect(csp).not.toMatch(/unsafe-inline|unsafe-eval|\*/)
  })

  it('sends no referrer to other sites but keeps the Origin of same-origin POSTs', () => {
    // The backend accepts login and logout only with the exact frontend origin (VercelProxyIT). The
    // Fetch standard serializes the Origin of POSTs from a `no-referrer` page as `null`, which the
    // backend rejects; `same-origin` never does that for the page's own requests.
    expect(headersFor('/')['referrer-policy']).toBe('same-origin')
    expect(headersFor('/')).toMatchObject({ 'x-content-type-options': 'nosniff', 'x-frame-options': 'DENY' })
  })

  it('caches only the content-hashed build files for long', () => {
    expect(headersFor('/assets/index-a1b2c3.js')['cache-control']).toBe('public, max-age=31536000, immutable')
    expect(headersFor('/index.html')['cache-control']).toBeUndefined()
    expect(headersFor('/login')['cache-control']).toBeUndefined()
  })
})

describe('vercel.json build', () => {
  it('builds the Vite app from this directory and defines no environment variables', () => {
    expect(config).toMatchObject({
      framework: 'vite',
      installCommand: 'npm ci',
      buildCommand: 'npm run build',
      outputDirectory: 'dist',
    })
    // Variables belong in the dashboard; VITE_* ones end up in the public bundle.
    expect(config).not.toHaveProperty('env')
    expect(config).not.toHaveProperty('build')
    expect(vercelJson).not.toMatch(/VITE_|secret|password|token/i)
  })
})
