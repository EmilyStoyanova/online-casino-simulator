import { describe, expect, it } from 'vitest'
import { alice } from '../test/fixtures'
import { jsonResponse, noSession, stubApi } from '../test/http'
import { ApiError } from './apiClient'
import { getCurrentUser } from './userApi'

describe('userApi', () => {
  it('reads the profile of the session with the cookies and without a CSRF token', async () => {
    const api = stubApi({ 'GET /api/v1/users/me': () => jsonResponse(alice) })

    await expect(getCurrentUser()).resolves.toEqual(alice)

    expect(api.requests.map((r) => `${r.method} ${r.path}`)).toEqual(['GET /api/v1/users/me'])
    expect(api.fetchMock).toHaveBeenCalledWith('/api/v1/users/me', expect.objectContaining({ credentials: 'include' }))
    expect(api.requestsTo('GET /api/v1/users/me')[0]?.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('reports a missing session as 401 with its code', async () => {
    stubApi({ 'GET /api/v1/users/me': noSession() })

    const error = await getCurrentUser().catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' })
  })
})
