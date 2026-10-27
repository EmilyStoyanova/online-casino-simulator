import { describe, expect, it, vi } from 'vitest'
import { jsonResponse, stubFetch } from '../test/http'
import { ApiError, apiGet, resolveApiUrl } from './apiClient'

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
