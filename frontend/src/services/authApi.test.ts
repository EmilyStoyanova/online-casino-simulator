import { describe, expect, it } from 'vitest'
import { alice, PASSWORD } from '../test/fixtures'
import { csrfEndpoint, deleteCookie, noContent, problemResponse, signedIn, stubApi } from '../test/http'
import { readCsrfToken } from './csrf'
import { login, logout, register } from './authApi'

describe('authApi', () => {
  it('registers with the CSRF token and fetches a new token afterwards', async () => {
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'POST /api/v1/auth/register': signedIn(alice, 201),
    })
    const request = { email: 'alice@example.com', password: PASSWORD, displayName: 'Alice', adultConfirmed: true }

    await expect(register(request)).resolves.toEqual(alice)

    expect(api.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'GET /api/v1/auth/csrf',
      'POST /api/v1/auth/register',
      'GET /api/v1/auth/csrf',
    ])
    expect(api.requestsTo('POST /api/v1/auth/register')[0]).toMatchObject({
      headers: { 'X-XSRF-TOKEN': 'csrf-1' },
      body: request,
    })
    // The token deleted by the server has been replaced.
    expect(readCsrfToken()).toBe('csrf-2')
  })

  it('logs in and replaces the deleted token', async () => {
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'POST /api/v1/auth/login': signedIn(alice),
    })

    await expect(login({ email: 'alice@example.com', password: PASSWORD })).resolves.toEqual(alice)

    expect(api.requestsTo('POST /api/v1/auth/login')[0]?.body).toEqual({ email: 'alice@example.com', password: PASSWORD })
    expect(readCsrfToken()).toBe('csrf-2')
  })

  it('logs out with the token and fetches a new one', async () => {
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'POST /api/v1/auth/logout': () => {
        deleteCookie('XSRF-TOKEN')
        return noContent()
      },
    })

    await expect(logout()).resolves.toBeUndefined()

    expect(api.requestsTo('POST /api/v1/auth/logout')[0]?.headers['X-XSRF-TOKEN']).toBe('csrf-1')
    expect(readCsrfToken()).toBe('csrf-2')
  })

  it('does not fail a successful login when the new token cannot be fetched', async () => {
    stubApi({
      'GET /api/v1/auth/csrf': [csrfEndpoint(), () => problemResponse(500, 'INTERNAL_ERROR')],
      'POST /api/v1/auth/login': signedIn(alice),
    })

    await expect(login({ email: 'alice@example.com', password: PASSWORD })).resolves.toEqual(alice)
    expect(readCsrfToken()).toBeNull()
  })

  it('passes failures on and keeps the token', async () => {
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'POST /api/v1/auth/login': () => problemResponse(401, 'INVALID_CREDENTIALS'),
    })

    await expect(login({ email: 'alice@example.com', password: 'wrong' })).rejects.toMatchObject({
      status: 401,
      code: 'INVALID_CREDENTIALS',
    })
    expect(api.requestsTo('GET /api/v1/auth/csrf')).toHaveLength(1)
    expect(readCsrfToken()).toBe('csrf-1')
  })
})
