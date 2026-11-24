import { useId } from 'react'
import { secondaryButton } from '../../components/buttonStyles'
import { card } from '../../components/cardStyles'
import { Coins } from '../../components/Coins'
import { FormAlert } from '../../components/form/FormAlert'
import { useWallet } from './useWallet'

const timestampFormat = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' })

function formatTimestamp(isoDate: string): string {
  const date = new Date(isoDate)
  return Number.isNaN(date.getTime()) ? '—' : timestampFormat.format(date)
}

const compact = 'px-3 py-1.5'

/**
 * The player's balance as a card (§6.1): the lobby, the roulette page and the wallet page. A
 * placeholder while the first read is in flight (never a number), "Balance unavailable" with a retry
 * after a failed read, and the last confirmed value during a refresh. A failed refresh keeps that
 * value but says that it may be out of date. Renders nothing while the wallet is inactive.
 */
export function BalanceCard() {
  const { state, refresh } = useWallet()
  const headingId = useId()

  if (state.status === 'inactive') return null
  const busy = state.status === 'loading' || (state.status === 'loaded' && state.refresh === 'refreshing')

  return (
    <section aria-labelledby={headingId} aria-busy={busy} className={`${card} flex flex-col`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold text-zinc-100">
          Your balance
        </h2>
        {state.status === 'loaded' && (
          <button
            type="button"
            onClick={refresh}
            disabled={state.refresh === 'refreshing'}
            className={`${secondaryButton} ${compact}`}
          >
            {state.refresh === 'refreshing' ? 'Refreshing…' : 'Refresh'}
          </button>
        )}
      </div>

      {state.status === 'loading' && (
        <div className="mt-3 space-y-2">
          <div aria-hidden="true" className="h-9 w-40 animate-pulse rounded-lg bg-zinc-800" />
          <p className="text-sm text-zinc-400">Loading your balance…</p>
        </div>
      )}

      {state.status === 'failed' && (
        <div className="mt-3 space-y-3">
          <p className="text-2xl font-semibold text-zinc-100">Balance unavailable</p>
          <p className="text-sm text-zinc-400">{state.message}</p>
          <div>
            <button type="button" onClick={refresh} className={`${secondaryButton} ${compact}`}>
              Try again
            </button>
          </div>
        </div>
      )}

      {state.status === 'loaded' && (
        <div className="mt-3 space-y-2">
          <p className="text-3xl font-bold tracking-tight text-zinc-50">
            <Coins amount={state.balance} />
          </p>
          <p className="text-xs text-zinc-400">
            {state.updatedAt === null
              ? 'Confirmed by the server with your last round.'
              : `Updated ${formatTimestamp(state.updatedAt)}`}
          </p>
          {state.refresh === 'failed' && (
            <FormAlert tone="error">
              Your balance could not be refreshed, so it may be out of date.{' '}
              <button type="button" onClick={refresh} className="font-semibold underline underline-offset-2">
                Try again
              </button>
            </FormAlert>
          )}
        </div>
      )}

      <p className="mt-4 text-xs text-zinc-500">Virtual coins have no monetary value.</p>
    </section>
  )
}
