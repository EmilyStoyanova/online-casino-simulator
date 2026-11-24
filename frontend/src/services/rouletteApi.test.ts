import { describe, expect, it, vi } from 'vitest'
import { SPIN_KEY, spinRequest, spinResult } from '../test/fixtures'
import { csrfEndpoint, jsonResponse, problemResponse, setCookie, stubApi, stubFetch } from '../test/http'
import type { RouletteSpinRequest } from '../types/roulette'
import { ApiError, onSessionExpired } from './apiClient'
import { IDEMPOTENCY_KEY_HEADER, spinRoulette } from './rouletteApi'

const SPIN = 'POST /api/v1/games/roulette/spins'

describe('spinRoulette', () => {
  it('posts the bets with the CSRF token and the caller’s Idempotency-Key and returns the server’s round', async () => {
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      [SPIN]: () => jsonResponse(spinResult),
    })

    await expect(spinRoulette(spinRequest, SPIN_KEY)).resolves.toEqual(spinResult)

    const [spin] = api.requestsTo(SPIN)
    expect(IDEMPOTENCY_KEY_HEADER).toBe('Idempotency-Key')
    expect(spin?.headers).toEqual({
      'Idempotency-Key': SPIN_KEY,
      Accept: 'application/json, application/problem+json',
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': 'csrf-1',
    })
    expect(api.fetchMock).toHaveBeenCalledWith(
      '/api/v1/games/roulette/spins',
      expect.objectContaining({ method: 'POST', credentials: 'include', signal: undefined }),
    )
  })

  it('serializes exactly the bets: each category with only its own option property, integers unchanged', async () => {
    setCookie('XSRF-TOKEN', 'token')
    const api = stubApi({ [SPIN]: () => jsonResponse(spinResult) })
    const allCategories: RouletteSpinRequest = {
      bets: [
        { category: 'STRAIGHT', number: 0, amount: 1 },
        { category: 'RED_BLACK', color: 'BLACK', amount: 1000 },
        { category: 'EVEN_ODD', parity: 'ODD', amount: 5 },
        { category: 'LOW_HIGH', range: 'HIGH', amount: 25 },
        { category: 'DOZEN', dozen: 'THIRD', amount: 100 },
      ],
    }

    await spinRoulette(allCategories, SPIN_KEY)

    const sent = api.fetchMock.mock.calls[0]?.[1]?.body
    expect(sent).toBe(
      '{"bets":[{"category":"STRAIGHT","number":0,"amount":1},{"category":"RED_BLACK","color":"BLACK","amount":1000},' +
        '{"category":"EVEN_ODD","parity":"ODD","amount":5},{"category":"LOW_HIGH","range":"HIGH","amount":25},' +
        '{"category":"DOZEN","dozen":"THIRD","amount":100}]}',
    )
  })

  it('sends a frozen request unchanged', async () => {
    setCookie('XSRF-TOKEN', 'token')
    const api = stubApi({ [SPIN]: () => jsonResponse(spinResult) })
    const frozen: RouletteSpinRequest = Object.freeze({ bets: Object.freeze(spinRequest.bets.map((bet) => Object.freeze({ ...bet }))) })

    await spinRoulette(frozen, SPIN_KEY)

    expect(api.requestsTo(SPIN)[0]?.body).toEqual(spinRequest)
  })

  it('repeats the same key and body after 403 CSRF_TOKEN_INVALID (the rejected request played nothing)', async () => {
    setCookie('XSRF-TOKEN', 'stale')
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      [SPIN]: [() => problemResponse(403, 'CSRF_TOKEN_INVALID'), () => jsonResponse(spinResult)],
    })

    await expect(spinRoulette(spinRequest, SPIN_KEY)).resolves.toEqual(spinResult)

    const spins = api.requestsTo(SPIN)
    expect(spins.map((spin) => spin.headers['Idempotency-Key'])).toEqual([SPIN_KEY, SPIN_KEY])
    expect(spins.map((spin) => spin.body)).toEqual([spinRequest, spinRequest])
    expect(spins.map((spin) => spin.headers['X-XSRF-TOKEN'])).toEqual(['stale', 'csrf-1'])
  })

  it('creates and remembers no key itself: every call sends exactly the key the caller passes', async () => {
    setCookie('XSRF-TOKEN', 'token')
    const api = stubApi({ [SPIN]: [() => problemResponse(503, 'SERVICE_UNAVAILABLE'), () => jsonResponse(spinResult)] })
    const nextSpin = '0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10'

    await spinRoulette(spinRequest, SPIN_KEY).catch(() => undefined)
    await spinRoulette(spinRequest, SPIN_KEY) // the caller's retry of the same attempt
    await spinRoulette(spinRequest, nextSpin) // a genuinely new spin

    expect(api.requestsTo(SPIN).map((spin) => spin.headers['Idempotency-Key'])).toEqual([SPIN_KEY, SPIN_KEY, nextSpin])
  })

  it('accepts the canonical key in upper case and sends it unchanged', async () => {
    setCookie('XSRF-TOKEN', 'token')
    const api = stubApi({ [SPIN]: () => jsonResponse(spinResult) })

    await spinRoulette(spinRequest, SPIN_KEY.toUpperCase())

    expect(api.requestsTo(SPIN)[0]?.headers['Idempotency-Key']).toBe(SPIN_KEY.toUpperCase())
  })

  it.each(['', '1-1-1-1-1', '3f2b8c4e9d1a4e7b8c2f5a6d7e8f9a0b', `{${SPIN_KEY}}`, ` ${SPIN_KEY}`])(
    'rejects the non-canonical key "%s" without sending anything',
    async (key) => {
      const api = stubApi({ [SPIN]: () => jsonResponse(spinResult) })

      await expect(spinRoulette(spinRequest, key)).rejects.toBeInstanceOf(TypeError)
      expect(api.fetchMock).not.toHaveBeenCalled()
    },
  )

  it.each([
    [400, 'VALIDATION_FAILED', { errors: [{ field: 'bets[1].color', message: 'must not be null' }] }],
    [400, 'MALFORMED_REQUEST', {}],
    [403, 'ACCESS_DENIED', {}],
    [404, 'NOT_FOUND', {}],
    [409, 'INSUFFICIENT_FUNDS', {}],
    [409, 'IDEMPOTENCY_KEY_REUSED', {}],
    [409, 'BALANCE_LIMIT_EXCEEDED', {}],
    [500, 'INTERNAL_ERROR', {}],
    [503, 'SERVICE_UNAVAILABLE', {}],
  ])('rejects %i %s with an ApiError carrying the Problem Details', async (status, code, extra) => {
    setCookie('XSRF-TOKEN', 'token')
    stubApi({ [SPIN]: () => problemResponse(status, code, extra) })

    const error = (await spinRoulette(spinRequest, SPIN_KEY).catch((caught: unknown) => caught)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(status)
    expect(error.code).toBe(code)
    // The backend's own errors carry Problem Details, so they are never "unavailable".
    expect(error.isUnavailable).toBe(false)
    expect(error.problem?.errors).toEqual((extra as { errors?: unknown }).errors)
  })

  it('reports a lost connection as a network error (outcome unknown to the caller)', async () => {
    setCookie('XSRF-TOKEN', 'token')
    stubFetch().mockRejectedValue(new TypeError('Failed to fetch'))

    const error = (await spinRoulette(spinRequest, SPIN_KEY).catch((caught: unknown) => caught)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 0, isNetworkError: true, isUnavailable: true })
  })

  it('reports a proxy answer without Problem Details (e.g. Vercel 504) as unavailable', async () => {
    setCookie('XSRF-TOKEN', 'token')
    stubFetch().mockResolvedValue(new Response('<html>timeout</html>', { status: 504, headers: { 'Content-Type': 'text/html' } }))

    const error = (await spinRoulette(spinRequest, SPIN_KEY).catch((caught: unknown) => caught)) as ApiError

    expect(error).toMatchObject({ status: 504, isUnavailable: true, code: undefined })
  })

  it('reports 401 SESSION_EXPIRED to the session listener like every other request', async () => {
    setCookie('XSRF-TOKEN', 'token')
    stubApi({ [SPIN]: () => problemResponse(401, 'SESSION_EXPIRED') })
    const listener = vi.fn()
    const unsubscribe = onSessionExpired(listener)

    await expect(spinRoulette(spinRequest, SPIN_KEY)).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED' })
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
  })
})
