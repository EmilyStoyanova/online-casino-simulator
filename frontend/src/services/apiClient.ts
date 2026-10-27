/**
 * Thin, typed wrapper around `fetch` for calls to the backend API.
 *
 * All game outcomes and balances are decided by the backend; this client only transports
 * user actions and server-confirmed results.
 */

/** Error raised for non-2xx responses and network failures. */
export class ApiError extends Error {
  readonly status: number
  readonly body: unknown

  /** @param status HTTP status code, or `0` when the server could not be reached. */
  constructor(message: string, status: number, body: unknown = undefined) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }

  get isNetworkError(): boolean {
    return this.status === 0
  }
}

export interface RequestOptions {
  signal?: AbortSignal
}

export function resolveApiUrl(path: string): string {
  const baseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`
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

export async function apiGet<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(resolveApiUrl(path), {
      method: 'GET',
      headers: { Accept: 'application/json' },
      // Session cookies (introduced with authentication) must accompany every request.
      credentials: 'include',
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError('The server could not be reached. Please check your connection.', 0)
  }

  const body = await parseBody(response)
  if (!response.ok) {
    throw new ApiError(`Request failed with status ${response.status}`, response.status, body)
  }
  return body as T
}
