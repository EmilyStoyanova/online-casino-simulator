import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../../services/apiClient'
import { getWallet } from '../../services/walletApi'
import { useAuth } from '../auth/useAuth'
import { WalletContext, type WalletContextValue, type WalletState } from './walletContext'

const INACTIVE: WalletState = { status: 'inactive' }
const LOADING: WalletState = { status: 'loading' }

/**
 * The wallet of one player session. `session` changes whenever the player changes (sign-in,
 * sign-out, expired session, another account, a role without a wallet), so nothing of an earlier
 * session can reach a later one, even for the same user.
 */
interface Snapshot {
  owner: string | null
  session: number
  state: WalletState
}

function describeFailure(error: unknown): string {
  return error instanceof ApiError && error.isUnavailable ? error.message : 'Your balance could not be loaded.'
}

/**
 * Holds the balance of the signed-in player for the header, the lobby and the player pages (Q2, §9.2).
 * Render it inside `AuthProvider` and around everything that shows the balance.
 *
 * - Active only for an authenticated `PLAYER`; otherwise `inactive` without any request.
 * - One `GET /api/v1/wallet` per player session; navigation and other re-renders do not repeat it.
 * - Reads are numbered: an answer that is not the latest read is dropped, and a newer read aborts the
 *   older one. `applyConfirmedBalance` counts as newer than every read started before it (Q3).
 * - A change of player resets the state during render, before any child sees it.
 */
export function WalletProvider({ children }: { children: ReactNode }) {
  const { state: auth } = useAuth()
  const playerId = auth.status === 'authenticated' && auth.user.role === 'PLAYER' ? auth.user.id : null

  const [snapshot, setSnapshot] = useState<Snapshot>(() => ({
    owner: playerId,
    session: 0,
    state: playerId === null ? INACTIVE : LOADING,
  }))
  // Another player (or none): forget the previous session in this render (React's "adjust state when
  // a prop changes" pattern), so its balance is never rendered for the new one.
  if (snapshot.owner !== playerId) {
    setSnapshot({ owner: playerId, session: snapshot.session + 1, state: playerId === null ? INACTIVE : LOADING })
  }
  const session = snapshot.session
  const state = snapshot.owner === playerId ? snapshot.state : playerId === null ? INACTIVE : LOADING

  // The latest read; answers of any other read are ignored.
  const latestRead = useRef(0)
  const inFlight = useRef<AbortController | null>(null)
  // The session of the last committed render, for operations called later (events, spin results).
  const activeSession = useRef<number | null>(null)

  const startRead = useCallback((readSession: number) => {
    inFlight.current?.abort()
    const controller = new AbortController()
    inFlight.current = controller
    const read = ++latestRead.current

    getWallet({ signal: controller.signal })
      .then((wallet) => {
        if (read !== latestRead.current) return
        setSnapshot((previous) =>
          previous.session !== readSession
            ? previous
            : {
                ...previous,
                state: { status: 'loaded', balance: wallet.balance, updatedAt: wallet.updatedAt, refresh: 'idle' },
              },
        )
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || read !== latestRead.current) return
        setSnapshot((previous) => {
          if (previous.session !== readSession) return previous
          // A known balance stays visible, marked as possibly out of date; otherwise nothing is known.
          return previous.state.status === 'loaded'
            ? { ...previous, state: { ...previous.state, refresh: 'failed' } }
            : { ...previous, state: { status: 'failed', message: describeFailure(error) } }
        })
      })
  }, [])

  useEffect(() => {
    activeSession.current = playerId === null ? null : session
    if (playerId === null) return undefined
    startRead(session)
    return () => {
      // Leaving the session (or StrictMode's trial unmount): no answer may arrive any more.
      inFlight.current?.abort()
      latestRead.current += 1
      activeSession.current = null
    }
  }, [playerId, session, startRead])

  const refresh = useCallback(() => {
    if (playerId === null || activeSession.current !== session) return
    setSnapshot((previous) =>
      previous.session !== session
        ? previous
        : {
            ...previous,
            state: previous.state.status === 'loaded' ? { ...previous.state, refresh: 'refreshing' } : LOADING,
          },
    )
    startRead(session)
  }, [playerId, session, startRead])

  const applyConfirmedBalance = useCallback(
    (balance: number) => {
      if (!Number.isSafeInteger(balance) || balance < 0) {
        throw new RangeError('A confirmed balance must be a whole number of coins from 0 to 2^53 − 1.')
      }
      // Bound to the session of the render that created this function: a later session ignores it.
      if (playerId === null || activeSession.current !== session) return
      setSnapshot((previous) =>
        previous.session !== session
          ? previous
          : // The server's value as it is; no timestamp is invented for it.
            { ...previous, state: { status: 'loaded', balance, updatedAt: null, refresh: 'refreshing' } },
      )
      // Invalidates every earlier read and reconciles with the current server balance.
      startRead(session)
    },
    [playerId, session, startRead],
  )

  const value = useMemo<WalletContextValue>(
    () => ({ state, refresh, applyConfirmedBalance }),
    [state, refresh, applyConfirmedBalance],
  )
  return <WalletContext value={value}>{children}</WalletContext>
}
