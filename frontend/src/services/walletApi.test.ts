import { describe, expect, it } from 'vitest'
import { historyAfterSpin, startingWallet } from '../test/fixtures'
import { jsonResponse, problemResponse, stubApi, stubFetch } from '../test/http'
import type { WalletTransactionPage } from '../types/wallet'
import { ApiError } from './apiClient'
import { getWallet, listWalletTransactions } from './walletApi'

const WALLET = 'GET /api/v1/wallet'
const HISTORY = 'GET /api/v1/wallet/transactions'

describe('getWallet', () => {
  it('reads the balance of the session with the cookies, without a CSRF token or other headers', async () => {
    const api = stubApi({ [WALLET]: () => jsonResponse(startingWallet) })

    await expect(getWallet()).resolves.toEqual({ balance: 1000, updatedAt: '2026-11-12T09:15:00.123456Z' })

    expect(api.fetchMock).toHaveBeenCalledWith('/api/v1/wallet', expect.objectContaining({ method: 'GET', credentials: 'include' }))
    expect(api.requestsTo(WALLET)[0]?.headers).toEqual({ Accept: 'application/json, application/problem+json' })
  })

  it('reports an administrator as 403 ACCESS_DENIED', async () => {
    stubApi({ [WALLET]: () => problemResponse(403, 'ACCESS_DENIED') })

    const error = await getWallet().catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 403, code: 'ACCESS_DENIED', isUnavailable: false })
  })

  it('reports an unreachable server as unavailable', async () => {
    stubFetch().mockRejectedValue(new TypeError('Failed to fetch'))

    const error = (await getWallet().catch((caught: unknown) => caught)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.isNetworkError).toBe(true)
    expect(error.isUnavailable).toBe(true)
  })

  it('passes the AbortSignal through', async () => {
    const api = stubApi({ [WALLET]: () => jsonResponse(startingWallet) })
    const controller = new AbortController()

    await getWallet({ signal: controller.signal })

    expect(api.fetchMock).toHaveBeenCalledWith('/api/v1/wallet', expect.objectContaining({ signal: controller.signal }))
  })
})

describe('listWalletTransactions', () => {
  it('requests the first page with the server defaults when no query is given', async () => {
    const api = stubApi({ [HISTORY]: () => jsonResponse(historyAfterSpin) })

    await expect(listWalletTransactions()).resolves.toEqual(historyAfterSpin)

    expect(api.requests.map((request) => request.path)).toEqual(['/api/v1/wallet/transactions'])
    expect(api.requests[0]?.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('sends limit and the cursor of the previous page, URL-encoded', async () => {
    const page: WalletTransactionPage = { items: [], nextCursor: null }
    const api = stubApi({
      'GET /api/v1/wallet/transactions?limit=20&cursor=djE6Mw': () => jsonResponse(page),
      'GET /api/v1/wallet/transactions?cursor=a%2Bb%3D%26c': () => jsonResponse(page),
    })

    await listWalletTransactions({ limit: 20, cursor: 'djE6Mw' })
    // The cursor is opaque; whatever it contains reaches the server unchanged.
    await listWalletTransactions({ cursor: 'a+b=&c' })

    expect(api.requests).toHaveLength(2)
  })

  it('keeps entries of types this client does not know (shown generically, by the sign of amount)', async () => {
    const future: WalletTransactionPage = {
      items: [{ id: 9, type: 'LEARNING_REWARD', amount: 50, balanceAfter: 1050, createdAt: '2026-12-01T10:00:00Z' }],
      nextCursor: 'djE6OQ',
    }
    stubApi({ [HISTORY]: () => jsonResponse(future) })

    await expect(listWalletTransactions()).resolves.toEqual(future)
  })

  it('reports an invalid limit as 400 VALIDATION_FAILED with the field', async () => {
    stubApi({
      'GET /api/v1/wallet/transactions?limit=101': () =>
        problemResponse(400, 'VALIDATION_FAILED', { errors: [{ field: 'limit', message: 'must be less than or equal to 100' }] }),
    })

    const error = (await listWalletTransactions({ limit: 101 }).catch((caught: unknown) => caught)) as ApiError

    expect(error.code).toBe('VALIDATION_FAILED')
    expect(error.problem?.errors).toEqual([{ field: 'limit', message: 'must be less than or equal to 100' }])
  })
})
