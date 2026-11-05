import { describe, expect, it } from 'vitest'
import { alice } from '../test/fixtures'
import { csrfEndpoint, jsonResponse, noSession, stubApi } from '../test/http'
import { ApiError } from './apiClient'
import { getCurrentUser, updateCurrentUser } from './userApi'

describe('userApi', () => {
  it('reads the profile of the session with the cookies and without a CSRF token', async () => {
    const api = stubApi({ 'GET /api/v1/users/me': () => jsonResponse(alice) })

    await expect(getCurrentUser()).resolves.toEqual(alice)

    expect(api.requests.map((r) => `${r.method} ${r.path}`)).toEqual(['GET /api/v1/users/me'])
    expect(api.fetchMock).toHaveBeenCalledWith('/api/v1/users/me', expect.objectContaining({ credentials: 'include' }))
    expect(api.requestsTo('GET /api/v1/users/me')[0]?.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('sends a profile update as PATCH with the cookies and the CSRF token', async () => {
    const updated = { ...alice, displayName: 'Alice B.', interests: ['PROBABILITY'] }
    const api = stubApi({
      'GET /api/v1/auth/csrf': csrfEndpoint(),
      'PATCH /api/v1/users/me': () => jsonResponse(updated),
    })

    await expect(updateCurrentUser({ displayName: 'Alice B.', interests: ['PROBABILITY'] })).resolves.toEqual(updated)

    const [patch] = api.requestsTo('PATCH /api/v1/users/me')
    expect(patch?.body).toEqual({ displayName: 'Alice B.', interests: ['PROBABILITY'] })
    expect(patch?.headers).toMatchObject({ 'X-XSRF-TOKEN': 'csrf-1', 'Content-Type': 'application/json' })
    expect(api.fetchMock).toHaveBeenCalledWith(
      '/api/v1/users/me',
      expect.objectContaining({ method: 'PATCH', credentials: 'include' }),
    )
  })

  it('reports a missing session as 401 with its code', async () => {
    stubApi({ 'GET /api/v1/users/me': noSession() })

    const error = await getCurrentUser().catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' })
  })
})
