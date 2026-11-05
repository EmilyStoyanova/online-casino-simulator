/**
 * Thin, typed wrapper around `fetch` for calls to the backend API.
 *
 * All game outcomes and balances are decided by the backend; this client only transports
 * user actions and server-confirmed results.
 *
 * - Cookies (session, CSRF) accompany every request (`credentials: 'include'`); the session cookie is
 *   `HttpOnly`, so JavaScript never sees the session id.
 * - Unsafe methods send the CSRF token in `X-XSRF-TOKEN` and retry once with a new token when the
 *   server answers `403 CSRF_TOKEN_INVALID`.
 * - Error responses become an {@link ApiError} carrying the Problem Details body.
 */
import type { ProblemDetail } from '../types/problem'
import { CSRF_HEADER, CSRF_PATH, readCsrfToken } from './csrf'

/** Error raised for non-2xx responses and network failures. */
export class ApiError extends Error {
  readonly status: number
  readonly body: unknown
  /** Seconds from the `Retry-After` header (e.g. `429 TOO_MANY_ATTEMPTS`), if the server sent one. */
  readonly retryAfterSeconds: number | undefined

  /** @param status HTTP status code, or `0` when the request did not reach the server. */
  constructor(message: string, status: number, body: unknown = undefined, retryAfterSeconds?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.retryAfterSeconds = retryAfterSeconds
  }

  get isNetworkError(): boolean {
    return this.status === 0
  }

  /** The Problem Details body (RFC 9457), if the server sent one. */
  get problem(): ProblemDetail | undefined {
    return isProblemDetail(this.body) ? this.body : undefined
  }

  /** The stable error `code` of the Problem Details body; clients decide on it, never on the text. */
  get code(): string | undefined {
    return this.problem?.code
  }
}

export interface RequestOptions {
  signal?: AbortSignal
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

const UNSAFE_METHODS: ReadonlySet<Method> = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export function resolveApiUrl(path: string): string {
  const baseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`
}

export function isProblemDetail(value: unknown): value is ProblemDetail {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { code?: unknown }).code === 'string' &&
    typeof (value as { status?: unknown }).status === 'number'
  )
}

/** `Retry-After` in seconds: delta-seconds (as the backend sends) or an HTTP date. */
export function parseRetryAfter(value: string | null, now: number = Date.now()): number | undefined {
  if (value === null || value.trim() === '') return undefined
  const trimmed = value.trim()
  if (/^\d+$/.test(trimmed)) return Number(trimmed)
  // An HTTP date always names the weekday and month; anything else (e.g. "-5") is malformed.
  if (!/[a-z]/i.test(trimmed)) return undefined
  const date = Date.parse(trimmed)
  return Number.isNaN(date) ? undefined : Math.max(0, Math.ceil((date - now) / 1000))
}

const sessionExpiredListeners = new Set<() => void>()
// Incremented by every sign-in and sign-out (see sessionChanged).
let sessionGeneration = 0

/**
 * Calls `listener` whenever a response reports `401 SESSION_EXPIRED` (idle or absolute timeout, or a
 * deleted session). Responses to requests sent before the latest sign-in or sign-out are not
 * reported: they concern a session that has already been replaced. Returns a function that removes
 * the listener.
 */
export function onSessionExpired(listener: () => void): () => void {
  sessionExpiredListeners.add(listener)
  return () => {
    sessionExpiredListeners.delete(listener)
  }
}

/** Called after every successful sign-in and sign-out: the browser now holds a different session. */
export function sessionChanged(): void {
  sessionGeneration += 1
}

async function parseBody(response: Response): Promise<unknown> {
  const contentType = response.headers.get('Content-Type') ?? ''
  if (contentType.includes('json')) {
    try {
      return await response.json()
    } catch {
      return undefined
    }
  }
  const text = await response.text()
  return text === '' ? undefined : text
}

async function request<T>(
  method: Method,
  path: string,
  body: unknown,
  options: RequestOptions,
  retryOnCsrfError = true,
): Promise<T> {
  const sentWithSession = sessionGeneration
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (UNSAFE_METHODS.has(method)) {
    headers[CSRF_HEADER] = await ensureCsrfToken()
  }

  let response: Response
  try {
    response = await fetch(resolveApiUrl(path), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'include',
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError('The server could not be reached. Please check your connection.', 0)
  }

  const responseBody = await parseBody(response)
  if (response.ok) {
    return responseBody as T
  }
  const error = new ApiError(
    `Request failed with status ${response.status}`,
    response.status,
    responseBody,
    parseRetryAfter(response.headers.get('Retry-After')),
  )
  if (error.code === 'CSRF_TOKEN_INVALID' && retryOnCsrfError && UNSAFE_METHODS.has(method)) {
    // The token cookie may have been deleted or replaced meanwhile (e.g. sign-in in another tab).
    await refreshCsrfToken()
    return request<T>(method, path, body, options, false)
  }
  if (error.code === 'SESSION_EXPIRED' && sentWithSession === sessionGeneration) {
    sessionExpiredListeners.forEach((listener) => listener())
  }
  throw error
}

export function apiGet<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return request<T>('GET', path, undefined, options)
}

/** A `POST` with an optional JSON body and the CSRF header. */
export function apiPost<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
  return request<T>('POST', path, body, options)
}

/** A `PATCH` with a JSON body and the CSRF header. */
export function apiPatch<T>(path: string, body: unknown, options: RequestOptions = {}): Promise<T> {
  return request<T>('PATCH', path, body, options)
}

let pendingToken: Promise<string> | null = null

/** The current CSRF token, fetched from `GET /api/v1/auth/csrf` if there is no cookie yet. */
export function ensureCsrfToken(): Promise<string> {
  const token = readCsrfToken()
  return token === null ? refreshCsrfToken() : Promise.resolve(token)
}

/**
 * Fetches a new CSRF token (the server sets the `XSRF-TOKEN` cookie). Concurrent calls share one
 * request.
 */
export function refreshCsrfToken(): Promise<string> {
  pendingToken ??= (async () => {
    try {
      await request<void>('GET', CSRF_PATH, undefined, {})
      const token = readCsrfToken()
      if (token === null) {
        // E.g. the API runs on another site, whose cookies JavaScript cannot read (ADR-0006, Week 12).
        throw new ApiError('The security token could not be read. Reload the page and try again.', 0)
      }
      return token
    } finally {
      pendingToken = null
    }
  })()
  return pendingToken
}
