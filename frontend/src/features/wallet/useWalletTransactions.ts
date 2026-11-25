import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../services/apiClient'
import { listWalletTransactions } from '../../services/walletApi'
import type { WalletTransaction, WalletTransactionPage } from '../../types/wallet'
import { useAuth } from '../auth/useAuth'
import { useWallet } from './useWallet'

/** Entries per page (the contract's default, sent explicitly). */
export const HISTORY_PAGE_SIZE = 20

/**
 * The wallet history of the signed-in player, in memory only.
 *
 * - `inactive`: no player session; nothing is requested.
 * - `loading`: the first page is in flight; nothing is known yet.
 * - `failed`: the first page could not be loaded; nothing is shown as history.
 * - `loaded`: the entries loaded so far, newest first, and the cursor of the next (older) page
 *   (`null`: the oldest entry is loaded). `older` is the state of "Load older entries"; `refresh` the
 *   state of a reload of the first page. While either runs, the loaded entries stay visible, and a
 *   failure keeps them (`failed`, with `error`).
 */
export type HistoryState =
  | { status: 'inactive' }
  | { status: 'loading' }
  | { status: 'failed'; message: string }
  | {
      status: 'loaded'
      items: readonly WalletTransaction[]
      nextCursor: string | null
      older: 'idle' | 'loading' | 'failed'
      refresh: 'idle' | 'refreshing' | 'failed'
      /** The message of the last failed "load older" (shown with its retry). */
      error: string | null
    }

export interface WalletTransactions {
  state: HistoryState
  /** Loads the next older page with the last `nextCursor`. Ignored while any request is in flight. */
  loadOlder: () => void
  /**
   * Reloads the first page (new entries appear at the top, older pages are dropped) and the shared
   * balance of `WalletProvider`. After a failed first load, it is the retry. Ignored while a first
   * page is already in flight; it replaces a "load older" in flight.
   */
  refresh: () => void
}

type RequestKind = 'first' | 'older'

/** The history of one player session; see `WalletProvider` for the same pattern. */
interface Snapshot {
  owner: string | null
  session: number
  state: HistoryState
}

const INACTIVE: HistoryState = { status: 'inactive' }
const LOADING: HistoryState = { status: 'loading' }

function describeFailure(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.isUnavailable ? error.message : fallback
}

/** Appends an older page; an entry already shown is never shown twice (keyset pages do not repeat). */
function append(items: readonly WalletTransaction[], page: WalletTransactionPage): WalletTransaction[] {
  const known = new Set(items.map((item) => item.id))
  return [...items, ...page.items.filter((item) => !known.has(item.id))]
}

function firstPage(page: WalletTransactionPage): HistoryState {
  return { status: 'loaded', items: page.items, nextCursor: page.nextCursor, older: 'idle', refresh: 'idle', error: null }
}

/**
 * The paginated wallet history for `WalletPage` (§6.2, Q1): `GET /api/v1/wallet/transactions` with
 * `limit=20`, then `cursor=nextCursor` (passed back unchanged) for older pages, appended in order.
 *
 * - One request at a time: "load older" waits for nothing and is ignored while a request is in flight;
 *   a refresh supersedes a "load older" (its answer is dropped). Every request is numbered and only the
 *   latest one may change the state; a superseded request is aborted.
 * - Bound to the player session: another player, a sign-out or an expired session resets the state
 *   during render (nothing of the earlier session is rendered), and every answer is checked against
 *   the session it was requested for.
 */
export function useWalletTransactions(): WalletTransactions {
  const { state: auth } = useAuth()
  const { refresh: refreshBalance } = useWallet()
  const playerId = auth.status === 'authenticated' && auth.user.role === 'PLAYER' ? auth.user.id : null

  const [snapshot, setSnapshot] = useState<Snapshot>(() => ({
    owner: playerId,
    session: 0,
    state: playerId === null ? INACTIVE : LOADING,
  }))
  if (snapshot.owner !== playerId) {
    setSnapshot({ owner: playerId, session: snapshot.session + 1, state: playerId === null ? INACTIVE : LOADING })
  }
  const session = snapshot.session
  const state = snapshot.owner === playerId ? snapshot.state : playerId === null ? INACTIVE : LOADING

  const latestRequest = useRef(0)
  const inFlight = useRef<{ kind: RequestKind; controller: AbortController } | null>(null)
  const activeSession = useRef<number | null>(null)

  const send = useCallback((kind: RequestKind, requestSession: number, cursor?: string) => {
    inFlight.current?.controller.abort()
    const controller = new AbortController()
    inFlight.current = { kind, controller }
    const request = ++latestRequest.current
    const update = (next: (previous: HistoryState) => HistoryState) => {
      inFlight.current = null
      setSnapshot((previous) => (previous.session !== requestSession ? previous : { ...previous, state: next(previous.state) }))
    }

    listWalletTransactions(
      cursor === undefined ? { limit: HISTORY_PAGE_SIZE } : { limit: HISTORY_PAGE_SIZE, cursor },
      { signal: controller.signal },
    )
      .then((page) => {
        if (request !== latestRequest.current) return
        update((previous) =>
          kind === 'first' || previous.status !== 'loaded'
            ? firstPage(page)
            : { ...previous, items: append(previous.items, page), nextCursor: page.nextCursor, older: 'idle', error: null },
        )
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || request !== latestRequest.current) return
        update((previous) => {
          if (previous.status !== 'loaded') {
            return { status: 'failed', message: describeFailure(error, 'Your transaction history could not be loaded.') }
          }
          // The entries already shown stay; only the failed step is marked.
          return kind === 'first'
            ? { ...previous, refresh: 'failed' }
            : { ...previous, older: 'failed', error: describeFailure(error, 'Older entries could not be loaded.') }
        })
      })
  }, [])

  useEffect(() => {
    activeSession.current = playerId === null ? null : session
    if (playerId === null) return undefined
    send('first', session)
    return () => {
      // Leaving the session or the page (or StrictMode's trial unmount): no answer may arrive any more.
      inFlight.current?.controller.abort()
      inFlight.current = null
      latestRequest.current += 1
      activeSession.current = null
    }
  }, [playerId, session, send])

  const loadOlder = useCallback(() => {
    if (activeSession.current !== session || inFlight.current !== null) return
    if (state.status !== 'loaded' || state.nextCursor === null) return
    const cursor = state.nextCursor
    setSnapshot((previous) =>
      previous.session !== session || previous.state.status !== 'loaded'
        ? previous
        : { ...previous, state: { ...previous.state, older: 'loading', error: null } },
    )
    send('older', session, cursor)
  }, [session, state, send])

  const refresh = useCallback(() => {
    if (activeSession.current !== session || inFlight.current?.kind === 'first') return
    setSnapshot((previous) => {
      if (previous.session !== session) return previous
      return previous.state.status === 'loaded'
        ? // A "load older" in flight is replaced: the refreshed first page starts the list again.
          { ...previous, state: { ...previous.state, refresh: 'refreshing', older: 'idle', error: null } }
        : { ...previous, state: LOADING }
    })
    send('first', session)
    refreshBalance()
  }, [session, send, refreshBalance])

  return { state, loadOlder, refresh }
}
