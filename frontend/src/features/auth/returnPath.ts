import { useSearchParams } from 'react-router'

/**
 * Where to go after signing in (AC-11.2, S17). The login and registration pages carry the page the
 * user asked for in `?returnTo=`; anyone can put a value there, so only paths of this application are
 * accepted, never another site.
 */
export const RETURN_TO_PARAM = 'returnTo'

const HOME = '/'
/** Placeholder origin to resolve a path against; only paths that stay on it are internal. */
const BASE = 'https://app.invalid'
/** Returning to these would show a sign-in form again. */
const AUTH_PAGES: ReadonlySet<string> = new Set(['/login', '/register'])

/**
 * `value` if it is a path of this application (with query and fragment), otherwise `/`.
 *
 * Rejected: absolute URLs, protocol-relative `//host`, backslashes (browsers read `/\host` as
 * `//host`), control characters and whitespace (browsers drop tabs and line breaks, so `/\t/host`
 * would become `//host`), and the sign-in pages themselves.
 */
export function safeReturnPath(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\s\p{Cc}]/u.test(value)) {
    return HOME
  }
  let url: URL
  try {
    url = new URL(value, BASE)
  } catch {
    return HOME
  }
  if (url.origin !== BASE || AUTH_PAGES.has(url.pathname)) {
    return HOME
  }
  return `${url.pathname}${url.search}${url.hash}`
}

/** The login page, remembering `returnTo` unless it is the home page. */
export function loginPath(returnTo: string = HOME): string {
  return withReturnTo('/login', returnTo)
}

/** The registration page, remembering `returnTo` unless it is the home page. */
export function registerPath(returnTo: string = HOME): string {
  return withReturnTo('/register', returnTo)
}

function withReturnTo(page: string, returnTo: string): string {
  const safe = safeReturnPath(returnTo)
  return safe === HOME ? page : `${page}?${new URLSearchParams({ [RETURN_TO_PARAM]: safe })}`
}

/** The checked `?returnTo=` of the current page, `/` if there is none or it is not internal. */
export function useReturnPath(): string {
  const [searchParams] = useSearchParams()
  return safeReturnPath(searchParams.get(RETURN_TO_PARAM))
}
