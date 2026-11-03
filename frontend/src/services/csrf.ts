/**
 * CSRF token of the SPA flow (ADR-0006, `csrf.spa()`): the backend sets the `XSRF-TOKEN` cookie, which
 * JavaScript can read, and expects its value in the `X-XSRF-TOKEN` header of every unsafe request.
 * The token is not stored anywhere else; registration, login and logout delete the cookie, so a new
 * token is fetched afterwards (`refreshCsrfToken` in `apiClient.ts`).
 */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'
export const CSRF_PATH = '/api/v1/auth/csrf'

/** The current token from the `XSRF-TOKEN` cookie, or `null` if there is none. */
export function readCsrfToken(): string | null {
  for (const cookie of document.cookie.split(';')) {
    const separator = cookie.indexOf('=')
    if (separator > 0 && cookie.slice(0, separator).trim() === CSRF_COOKIE) {
      const value = decodeURIComponent(cookie.slice(separator + 1).trim())
      return value === '' ? null : value
    }
  }
  return null
}
