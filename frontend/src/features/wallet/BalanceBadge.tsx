import { Link } from 'react-router'
import { Coins } from '../../components/Coins'
import { useWallet } from './useWallet'

const pill =
  'inline-flex items-center gap-2 rounded-full border border-zinc-700 bg-zinc-900 px-3 py-1.5 text-sm text-zinc-100'
const retry =
  'rounded-full px-2.5 py-1 text-xs font-semibold text-emerald-300 ring-1 ring-emerald-500/40 hover:bg-emerald-500/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400'

/**
 * The player's balance in the header (§4.2), a link to the wallet. Nothing for visitors and
 * administrators; while the first read is in flight a labelled placeholder, never a number; after a
 * failed read "Balance unavailable" with a retry. During a refresh the last confirmed value stays (no
 * flicker); if that refresh fails, the value is marked "Not up to date".
 */
export function BalanceBadge() {
  const { state, refresh } = useWallet()

  switch (state.status) {
    case 'inactive':
      return null
    case 'loading':
      return (
        <p className={pill} aria-busy="true">
          <span className="text-zinc-400">Balance</span> <span className="text-zinc-400">Loading…</span>
        </p>
      )
    case 'failed':
      return (
        <div className={pill}>
          <span>Balance unavailable</span>
          <button type="button" onClick={refresh} className={retry} aria-label="Retry loading the balance">
            Retry
          </button>
        </div>
      )
    case 'loaded':
      return (
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to="/wallet"
            aria-busy={state.refresh === 'refreshing'}
            className={`${pill} hover:bg-zinc-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400`}
          >
            {/* A real space: the accessible name must read "Balance 1,000 coins", not "Balance1,000 coins". */}
            <span className="text-zinc-400">Balance</span> <Coins amount={state.balance} className="font-semibold" />
          </Link>
          {state.refresh === 'failed' && (
            <>
              <span className="text-xs text-amber-300">Not up to date</span>
              <button type="button" onClick={refresh} className={retry} aria-label="Retry loading the balance">
                Retry
              </button>
            </>
          )}
        </div>
      )
  }
}
