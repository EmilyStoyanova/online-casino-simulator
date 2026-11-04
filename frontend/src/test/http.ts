import { vi } from 'vitest'

/** Builds a JSON `Response` for stubbing `fetch` in tests. */
export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

/** A Problem Details response (`application/problem+json`) as the backend sends it. */
export function problemResponse(
  status: number,
  code: string,
  extra: Record<string, unknown> = {},
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify({ title: 'Error', status, code, ...extra }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', 'Cache-Control': 'no-store', ...headers },
  })
}

export function noContent(): Response {
  return new Response(null, { status: 204 })
}

/** Replaces the global `fetch` with a mock; restored automatically after each test. */
export function stubFetch() {
  const fetchMock = vi.fn<typeof fetch>()
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function setCookie(name: string, value: string): void {
  document.cookie = `${name}=${encodeURIComponent(value)}; path=/`
}

export function deleteCookie(name: string): void {
  document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
}

/** Deletes every cookie of the test document (run after each test). */
export function clearCookies(): void {
  for (const cookie of document.cookie.split(';')) {
    const name = cookie.split('=')[0]?.trim()
    if (name) deleteCookie(name)
  }
}

type Handler = (init: RequestInit) => Response | Promise<Response>

/** A request the fake backend received. */
export interface RecordedRequest {
  method: string
  path: string
  headers: Record<string, string>
  body: unknown
}

/**
 * A fake backend for `fetch`: routes such as `'POST /api/v1/auth/login'` map to handlers. An array of
 * handlers answers consecutive requests (the last one repeats). Requests to other routes fail.
 */
export function stubApi(routes: Record<string, Handler | Handler[]>) {
  const calls = new Map<string, number>()
  const requests: RecordedRequest[] = []
  const fetchMock = stubFetch()
  fetchMock.mockImplementation(async (input, init = {}) => {
    const method = init.method ?? 'GET'
    const path = String(input)
    const route = `${method} ${path}`
    requests.push({
      method,
      path,
      headers: { ...(init.headers as Record<string, string>) },
      body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
    })
    const handlers = routes[route]
    if (handlers === undefined) {
      throw new Error(`Unexpected request in test: ${route}`)
    }
    const list = Array.isArray(handlers) ? handlers : [handlers]
    const index = calls.get(route) ?? 0
    calls.set(route, index + 1)
    const handler = list[Math.min(index, list.length - 1)]
    if (handler === undefined) {
      throw new Error(`No handler for ${route}`)
    }
    return handler(init)
  })
  return {
    fetchMock,
    requests,
    /** The requests sent to one route, e.g. `'POST /api/v1/auth/login'`. */
    requestsTo: (route: string) => requests.filter((request) => `${request.method} ${request.path}` === route),
  }
}

/** `GET /api/v1/auth/csrf`: sets a new `XSRF-TOKEN` cookie per call (`csrf-1`, `csrf-2`, …). */
export function csrfEndpoint(): Handler {
  let issued = 0
  return () => {
    issued += 1
    setCookie('XSRF-TOKEN', `csrf-${issued}`)
    return noContent()
  }
}

/** A successful sign-in response: the server deletes `XSRF-TOKEN`, as Spring's CSRF strategy does. */
export function signedIn(body: unknown, status = 200): Handler {
  return () => {
    deleteCookie('XSRF-TOKEN')
    return jsonResponse(body, status)
  }
}

/** `GET /api/v1/users/me` without a valid session: `401 AUTHENTICATION_REQUIRED`. */
export function noSession(): Handler {
  return () => problemResponse(401, 'AUTHENTICATION_REQUIRED')
}
