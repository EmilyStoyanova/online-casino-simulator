import { act, render, waitFor } from '@testing-library/react'
import { StrictMode, type ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { admin, alice } from '../../test/fixtures'
import { jsonResponse, stubApi } from '../../test/http'
import type { UserProfile } from '../../types/user'
import type { WalletTransaction, WalletTransactionPage } from '../../types/wallet'
import { AuthContext, type AuthContextValue, type AuthState } from '../auth/authContext'
import { useWalletTransactions, type WalletTransactions } from './useWalletTransactions'
import { WalletContext, type WalletContextValue } from './walletContext'

/**
 * The hook with the auth and wallet contexts set by the test. The app unmounts the wallet page on a
 * sign-out or an expired session (`RequireAuth`); these tests also cover a page that stays mounted
 * while the player changes, which the hook must handle on its own.
 */

const FIRST = 'GET /api/v1/wallet/transactions?limit=20'
const OLDER = 'GET /api/v1/wallet/transactions?limit=20&cursor=older'

const bob: UserProfile = { ...alice, id: '7c1d2e3f-4a5b-4c6d-9e8f-0a1b2c3d4e5f', email: 'bob@example.com', displayName: 'Bob' }

type Handler = Exclude<Parameters<typeof stubApi>[0][string], readonly unknown[]>

const entry = (id: number): WalletTransaction => ({
  id,
  type: 'ROULETTE_STAKE',
  amount: -10,
  balanceAfter: 1000 - id,
  createdAt: '2026-11-20T10:15:30Z',
})
const page = (ids: number[], nextCursor: string | null = null): WalletTransactionPage => ({ items: ids.map(entry), nextCursor })

/** A handler whose answers the test sends later, one per request, in request order. */
function controlled() {
  const resolvers: ((response: Response) => void)[] = []
  return {
    handler: (() => new Promise<Response>((resolve) => resolvers.push(resolve))) as Handler,
    count: () => resolvers.length,
    respond: (index: number, response: Response) =>
      act(async () => {
        const resolve = resolvers[index]
        if (!resolve) throw new Error(`No request ${index} yet`)
        resolve(response)
      }),
  }
}

const signedIn = (user: UserProfile): AuthState => ({ status: 'authenticated', user })

function renderHook(initial: AuthState, { strict = false } = {}) {
  const latest: { current: WalletTransactions | null } = { current: null }
  const refreshBalance = vi.fn()
  const wallet: WalletContextValue = { state: { status: 'inactive' }, refresh: refreshBalance, applyConfirmedBalance: () => undefined }
  const auth = (state: AuthState) => ({ state }) as AuthContextValue

  function Probe() {
    latest.current = useWalletTransactions()
    return null
  }
  const tree = (state: AuthState): ReactNode => {
    const content = (
      <AuthContext value={auth(state)}>
        <WalletContext value={wallet}>
          <Probe />
        </WalletContext>
      </AuthContext>
    )
    return strict ? <StrictMode>{content}</StrictMode> : content
  }
  const view = render(tree(initial))
  const history = () => {
    if (!latest.current) throw new Error('not rendered')
    return latest.current
  }
  return { history, refreshBalance, switchTo: (state: AuthState) => view.rerender(tree(state)), unmount: view.unmount }
}

const ids = (history: WalletTransactions) =>
  history.state.status === 'loaded' ? history.state.items.map((item) => item.id) : history.state.status

describe('useWalletTransactions: player sessions', () => {
  it('requests nothing without a player (visitor, administrator, checking)', () => {
    const api = stubApi({})
    for (const state of [{ status: 'anonymous', reason: 'signed-out' }, signedIn(admin), { status: 'checking' }] as AuthState[]) {
      const { history, unmount } = renderHook(state)
      expect(history().state).toEqual({ status: 'inactive' })
      history().refresh()
      unmount()
    }
    expect(api.requests).toHaveLength(0)
  })

  it('resets at once for another player and ignores the late answer for the previous one', async () => {
    const first = controlled()
    stubApi({ [FIRST]: first.handler })
    const { history, switchTo } = renderHook(signedIn(alice))
    await waitFor(() => expect(first.count()).toBe(1))

    switchTo(signedIn(bob))

    expect(history().state).toEqual({ status: 'loading' })
    await waitFor(() => expect(first.count()).toBe(2))
    await first.respond(0, jsonResponse(page([40, 39], 'older')))
    expect(history().state).toEqual({ status: 'loading' })
    await first.respond(1, jsonResponse(page([7])))
    expect(ids(history())).toEqual([7])
  })

  it('treats sign-out and sign-in of the same player as a new session (A → none → A)', async () => {
    const first = controlled()
    stubApi({ [FIRST]: first.handler })
    const { history, switchTo } = renderHook(signedIn(alice))
    await waitFor(() => expect(first.count()).toBe(1))

    switchTo({ status: 'anonymous', reason: 'session-expired' })
    expect(history().state).toEqual({ status: 'inactive' })
    switchTo(signedIn(alice))
    await waitFor(() => expect(first.count()).toBe(2))
    await first.respond(0, jsonResponse(page([1])))

    expect(history().state).toEqual({ status: 'loading' })
    await first.respond(1, jsonResponse(page([3, 2, 1])))
    expect(ids(history())).toEqual([3, 2, 1])
  })

  it('drops the loaded entries when the player changes', async () => {
    stubApi({ [FIRST]: () => jsonResponse(page([5, 4])) })
    const { history, switchTo } = renderHook(signedIn(alice))
    await waitFor(() => expect(ids(history())).toEqual([5, 4]))

    switchTo(signedIn(bob))

    expect(history().state).toEqual({ status: 'loading' })
  })

  it('in StrictMode applies one first page: the trial request is aborted', async () => {
    const api = stubApi({ [FIRST]: () => jsonResponse(page([2, 1])) })
    renderHook(signedIn(alice), { strict: true })

    await waitFor(() => expect(api.requestsTo(FIRST).length).toBeGreaterThan(0))
    const signals = api.fetchMock.mock.calls.map(([, init]) => init?.signal)
    expect(signals.filter((signal) => signal?.aborted === false)).toHaveLength(1)
    expect(signals.length).toBeLessThanOrEqual(2)
  })
})

describe('useWalletTransactions: one request at a time', () => {
  it('sends one "load older" for repeated calls before the first answer', async () => {
    const older = controlled()
    const api = stubApi({ [FIRST]: () => jsonResponse(page([40, 39], 'older')), [OLDER]: older.handler })
    const { history } = renderHook(signedIn(alice))
    await waitFor(() => expect(ids(history())).toEqual([40, 39]))

    act(() => {
      history().loadOlder()
      history().loadOlder()
    })
    act(() => history().loadOlder())

    expect(api.requestsTo(OLDER)).toHaveLength(1)
    await older.respond(0, jsonResponse(page([38])))
    expect(ids(history())).toEqual([40, 39, 38])
  })

  it('never shows an entry twice, even if an older page repeats one', async () => {
    stubApi({ [FIRST]: () => jsonResponse(page([40, 39], 'older')), [OLDER]: () => jsonResponse(page([39, 38])) })
    const { history } = renderHook(signedIn(alice))
    await waitFor(() => expect(ids(history())).toEqual([40, 39]))

    act(() => history().loadOlder())

    await waitFor(() => expect(ids(history())).toEqual([40, 39, 38]))
  })

  it('sends one first page and one balance refresh for repeated refreshes', async () => {
    const first = controlled()
    const api = stubApi({ [FIRST]: first.handler })
    const { history, refreshBalance } = renderHook(signedIn(alice))
    await waitFor(() => expect(first.count()).toBe(1))
    await first.respond(0, jsonResponse(page([1])))

    act(() => {
      history().refresh()
      history().refresh()
    })

    expect(api.requestsTo(FIRST)).toHaveLength(2)
    expect(refreshBalance).toHaveBeenCalledTimes(1)
    await first.respond(1, jsonResponse(page([2, 1])))
    expect(ids(history())).toEqual([2, 1])
  })

  it('ignores "load older" while the first page is loading or on the last page', async () => {
    const first = controlled()
    const api = stubApi({ [FIRST]: first.handler, [OLDER]: () => jsonResponse(page([1])) })
    const { history } = renderHook(signedIn(alice))
    await waitFor(() => expect(first.count()).toBe(1))

    act(() => history().loadOlder())
    await first.respond(0, jsonResponse(page([2])))
    act(() => history().loadOlder())

    expect(api.requestsTo(OLDER)).toHaveLength(0)
  })

  it('sends nothing after unmount and ignores an answer that arrives later', async () => {
    const first = controlled()
    const api = stubApi({ [FIRST]: first.handler })
    const { history, unmount } = renderHook(signedIn(alice))
    await waitFor(() => expect(first.count()).toBe(1))
    const { refresh } = history()

    unmount()
    act(() => refresh())

    expect(api.requestsTo(FIRST)).toHaveLength(1)
    expect(api.fetchMock.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  })
})
