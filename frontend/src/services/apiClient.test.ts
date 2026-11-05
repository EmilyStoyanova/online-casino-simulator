import { describe, expect, it, vi } from 'vitest'
import { csrfEndpoint, jsonResponse, noContent, problemResponse, setCookie, stubApi, stubFetch } from '../test/http'
import {
  ApiError,
  apiGet,
  apiPost,
  ensureCsrfToken,
  onSessionExpired,
  parseRetryAfter,
  resolveApiUrl,
  sessionChanged,
} from './apiClient'

describe('resolveApiUrl', () => {
  it('uses a relative URL when no base URL is configured', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    expect(resolveApiUrl('/api/v1/system/status')).toBe('/api/v1/system/status')
  })

  it('prefixes the configured base URL without duplicating slashes', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.org/')
    expect(resolveApiUrl('/api/v1/wallet')).toBe('https://api.example.org/api/v1/wallet')
    expect(resolveApiUrl('api/v1/wallet')).toBe('https://api.example.org/api/v1/wallet')
  })
})

describe('apiGet', () => {
  it('returns the parsed JSON body and sends credentials', async () => {
    const fetchMock = stubFetch()
    fetchMock.mockResolvedValue(jsonResponse({ hello: 'world' }))

    await expect(apiGet<{ hello: string }>('/api/v1/test')).resolves.toEqual({ hello: 'world' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/test',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })

  it('sends no CSRF header and fetches no token for safe methods', async () => {
    const api = stubApi({ 'GET /api/v1/test': () => jsonResponse({}) })

    await apiGet('/api/v1/test')

    expect(api.requests).toHaveLength(1)
    expect(api.requests[0]?.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('throws an ApiError with status and body for non-2xx responses', async () => {
    stubFetch().mockResolvedValue(jsonResponse({ title: 'Not Found' }, 404))

    const error = await apiGet('/api/v1/missing').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 404, body: { title: 'Not Found' } })
  })

  it('throws a network ApiError when the server cannot be reached', async () => {
    stubFetch().mockRejectedValue(new TypeError('Failed to fetch'))

    const error = await apiGet('/api/v1/test').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).isNetworkError).toBe(true)
  })

  it('propagates aborts unchanged so callers can ignore them', async () => {
    stubFetch().mockRejectedValue(new DOMException('Aborted', 'AbortError'))

    await expect(apiGet('/api/v1/test')).rejects.toMatchObject({ name: 'AbortError' })
  })
})

describe('Problem Details', () => {
  it('exposes the code, the field errors and Retry-After', async () => {
    stubFetch().mockResolvedValue(
      problemResponse(429, 'TOO_MANY_ATTEMPTS', { detail: 'Too many attempts.' }, { 'Retry-After': '850' }),
    )

    const error = (await apiGet('/api/v1/test').catch((e: unknown) => e)) as ApiError

    expect(error.status).toBe(429)
    expect(error.code).toBe('TOO_MANY_ATTEMPTS')
    expect(error.problem?.detail).toBe('Too many attempts.')
    expect(error.retryAfterSeconds).toBe(850)
  })

  it('has no code for bodies that are not Problem Details', async () => {
    stubFetch().mockResolvedValue(new Response('Bad gateway', { status: 502 }))

    const error = (await apiGet('/api/v1/test').catch((e: unknown) => e)) as ApiError

    expect(error.problem).toBeUndefined()
    expect(error.code).toBeUndefined()
    expect(error.body).toBe('Bad gateway')
  })
})

describe('parseRetryAfter', () => {
  it('accepts delta-seconds and HTTP dates', () => {
    const now = Date.parse('2026-11-03T10:00:00Z')

    expect(parseRetryAfter('900', now)).toBe(900)
    expect(parseRetryAfter(' 0 ', now)).toBe(0)
    expect(parseRetryAfter('Tue, 03 Nov 2026 10:01:30 GMT', now)).toBe(90)
    expect(parseRetryAfter('Tue, 03 Nov 2026 09:00:00 GMT', now)).toBe(0)
  })

  it('ignores missing or malformed values', () => {
    expect(parseRetryAfter(null)).toBeUndefined()
    expect(parseRetryAfter('')).toBeUndefined()
    expect(parseRetryAfter('soon')).toBeUndefined()
    expect(parseRetryAfter('-5')).toBeUndefined()
  })
})

describe('CSRF', () => {
  it('fetches a token first when there is none and sends it in X-XSRF-TOKEN with the JSON body', async () => {
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'POST /api/v1/test': () => jsonResponse({ ok: true }),
    })

    await expect(apiPost('/api/v1/test', { a: 1 })).resolves.toEqual({ ok: true })

    expect(api.requests.map((request) => `${request.method} ${request.path}`)).toEqual([
      'GET /api/v1/auth/csrf',
      'POST /api/v1/test',
    ])
    const post = api.requestsTo('POST /api/v1/test')[0]
    expect(post?.headers).toMatchObject({ 'X-XSRF-TOKEN': 'csrf-1', 'Content-Type': 'application/json' })
    expect(post?.body).toEqual({ a: 1 })
    expect(api.fetchMock).toHaveBeenLastCalledWith('/api/v1/test', expect.objectContaining({ credentials: 'include' }))
  })

  it('reuses the token of the existing cookie', async () => {
    setCookie('XSRF-TOKEN', 'existing')
    const api = stubApi({ 'POST /api/v1/test': () => noContent() })

    await apiPost('/api/v1/test')

    expect(api.requests).toHaveLength(1)
    expect(api.requests[0]?.headers['X-XSRF-TOKEN']).toBe('existing')
    expect(api.requests[0]?.headers).not.toHaveProperty('Content-Type')
  })

  it('retries once with a new token after 403 CSRF_TOKEN_INVALID', async () => {
    setCookie('XSRF-TOKEN', 'stale')
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'POST /api/v1/test': [() => problemResponse(403, 'CSRF_TOKEN_INVALID'), () => jsonResponse({ ok: true })],
    })

    await expect(apiPost('/api/v1/test')).resolves.toEqual({ ok: true })

    expect(api.requestsTo('POST /api/v1/test').map((request) => request.headers['X-XSRF-TOKEN'])).toEqual([
      'stale',
      'csrf-1',
    ])
  })

  it('gives up after the retry fails again', async () => {
    setCookie('XSRF-TOKEN', 'stale')
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'POST /api/v1/test': () => problemResponse(403, 'CSRF_TOKEN_INVALID'),
    })

    await expect(apiPost('/api/v1/test')).rejects.toMatchObject({ status: 403, code: 'CSRF_TOKEN_INVALID' })
    expect(api.requestsTo('POST /api/v1/test')).toHaveLength(2)
  })

  it('does not retry other 403 errors', async () => {
    setCookie('XSRF-TOKEN', 'token')
    const api = stubApi({ 'POST /api/v1/test': () => problemResponse(403, 'ACCESS_DENIED') })

    await expect(apiPost('/api/v1/test')).rejects.toMatchObject({ code: 'ACCESS_DENIED' })
    expect(api.requests).toHaveLength(1)
  })

  it('shares one token request between concurrent callers', async () => {
    const api = stubApi({ 'GET /api/v1/auth/csrf': csrfEndpoint() })

    const tokens = await Promise.all([ensureCsrfToken(), ensureCsrfToken()])

    expect(tokens).toEqual(['csrf-1', 'csrf-1'])
    expect(api.requests).toHaveLength(1)
  })

  it('fails clearly when the token cookie cannot be read', async () => {
    stubApi({ 'GET /api/v1/auth/csrf': () => noContent() })

    await expect(ensureCsrfToken()).rejects.toMatchObject({ status: 0, message: expect.stringContaining('security token') })
  })
})

describe('onSessionExpired', () => {
  it('notifies listeners of 401 SESSION_EXPIRED until they unsubscribe', async () => {
    stubApi({
      'GET /api/v1/wallet': () => problemResponse(401, 'SESSION_EXPIRED'),
      'GET /api/v1/other': () => problemResponse(401, 'AUTHENTICATION_REQUIRED'),
    })
    const listener = vi.fn()
    const unsubscribe = onSessionExpired(listener)

    await apiGet('/api/v1/wallet').catch(() => undefined)
    await apiGet('/api/v1/other').catch(() => undefined)
    expect(listener).toHaveBeenCalledTimes(1)

    unsubscribe()
    await apiGet('/api/v1/wallet').catch(() => undefined)
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('ignores SESSION_EXPIRED of a request sent before the latest sign-in or sign-out', async () => {
    let respond: (response: Response) => void = () => undefined
    const api = stubApi({ 'GET /api/v1/wallet': () => new Promise<Response>((resolve) => (respond = resolve)) })
    const listener = vi.fn()
    const unsubscribe = onSessionExpired(listener)

    const late = apiGet('/api/v1/wallet').catch((error: unknown) => error)
    await vi.waitFor(() => expect(api.requestsTo('GET /api/v1/wallet')).toHaveLength(1))
    // E.g. a sign-in finished while the request was on its way; its answer concerns the old session.
    sessionChanged()
    respond(problemResponse(401, 'SESSION_EXPIRED'))

    // The caller still gets the error, but the new session is not reported as ended.
    await expect(late).resolves.toMatchObject({ status: 401, code: 'SESSION_EXPIRED' })
    expect(listener).not.toHaveBeenCalled()
    unsubscribe()
  })
})
