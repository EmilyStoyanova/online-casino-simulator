import { describe, expect, it } from 'vitest'
import { interestCatalog } from '../test/fixtures'
import { jsonResponse, stubApi } from '../test/http'
import { listInterests } from './interestApi'

describe('interestApi', () => {
  it('lists the catalogue in the order of the server, without a CSRF token', async () => {
    const api = stubApi({ 'GET /api/v1/interests': () => jsonResponse(interestCatalog) })

    await expect(listInterests()).resolves.toEqual(interestCatalog)

    expect(api.requestsTo('GET /api/v1/interests')[0]?.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })
})
