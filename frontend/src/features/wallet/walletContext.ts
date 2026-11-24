import { createContext } from 'react'

/**
 * What the SPA knows about the signed-in player's wallet. In memory only; every value comes from the
 * server (`GET /api/v1/wallet` or the `balance` of a confirmed spin). The client never computes one.
 *
 * - `inactive`: no confirmed player session (visitor, administrator, `checking`, `unknown`); no
 *   request is sent.
 * - `loading`: the first read of this session is in flight; nothing is known, so nothing is shown
 *   as a number.
 * - `loaded`: the last server-confirmed balance. `updatedAt` is the wallet's own timestamp, or `null`
 *   when the value came from a spin result and the reconciling read has not answered yet. `refresh`:
 *   `refreshing` while a newer read is in flight (the value stays visible), `failed` when that read
 *   failed (the value is then shown as possibly out of date).
 * - `failed`: the first read failed; no balance is known.
 */
export type WalletState =
  | { status: 'inactive' }
  | { status: 'loading' }
  | {
      status: 'loaded'
      balance: number
      updatedAt: string | null
      refresh: 'idle' | 'refreshing' | 'failed'
    }
  | { status: 'failed'; message: string }

export interface WalletContextValue {
  state: WalletState
  /** Reads the balance again (retry after a failure, manual refresh). No effect while `inactive`. */
  refresh: () => void
  /**
   * Shows the server-confirmed `balance` of a successful spin at once and reconciles it with a new
   * `GET /api/v1/wallet`. Every read started before is ignored. The function is bound to the player
   * session it was taken from: called after a sign-out, an expired session or a change of account, it
   * does nothing. Throws a `RangeError` for a value that is not a whole number of coins (0 … 2^53 − 1).
   */
  applyConfirmedBalance: (balance: number) => void
}

/**
 * Outside a `WalletProvider` the wallet is simply `inactive` (no request, nothing shown). The app
 * renders the provider in `App.tsx`; tests of single layouts or pages may render without it.
 */
export const WalletContext = createContext<WalletContextValue>({
  state: { status: 'inactive' },
  refresh: () => undefined,
  applyConfirmedBalance: () => undefined,
})
