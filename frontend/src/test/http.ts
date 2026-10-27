import { vi } from 'vitest'

/** Builds a JSON `Response` for stubbing `fetch` in tests. */
export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Replaces the global `fetch` with a mock; restored automatically after each test. */
export function stubFetch() {
  const fetchMock = vi.fn<typeof fetch>()
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
