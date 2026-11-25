import { useEffect, useId, useRef } from 'react'
import { secondaryButton, touchTarget } from '../../components/buttonStyles'
import { card } from '../../components/cardStyles'
import { Coins } from '../../components/Coins'
import { FormAlert } from '../../components/form/FormAlert'
import type { WalletTransaction } from '../../types/wallet'
import type { HistoryState } from './useWalletTransactions'
import { formatEntryTime, formatSignedCoins, transactionTypeLabel } from './walletFormat'

const compact = `px-3 py-1.5 ${touchTarget}`
/** The visible label of a value in the narrow (card) layout; screen readers use the column header. */
const cardLabel = 'text-xs font-medium text-zinc-500 md:hidden'
const cell = 'py-3 pr-4 align-top max-md:flex max-md:flex-col max-md:gap-0.5 max-md:p-0'
const numericCell = `${cell} tabular-nums md:text-right last:pr-0`

function EntryRow({ entry }: { entry: WalletTransaction }) {
  const credit = entry.amount > 0
  return (
    <tr
      role="row"
      className="focus:outline-2 focus:outline-offset-2 focus:outline-emerald-400 md:border-t md:border-zinc-800 max-md:grid max-md:grid-cols-2 max-md:gap-x-4 max-md:gap-y-3 max-md:rounded-xl max-md:border max-md:border-zinc-800 max-md:bg-zinc-950/40 max-md:p-4"
    >
      <td role="cell" className={`${cell} text-zinc-300`}>
        <span aria-hidden="true" className={cardLabel}>
          Time
        </span>
        <time dateTime={entry.createdAt}>{formatEntryTime(entry.createdAt)}</time>
      </td>
      <td role="cell" className={`${cell} text-zinc-100`}>
        <span aria-hidden="true" className={cardLabel}>
          Type
        </span>
        {transactionTypeLabel(entry.type)}
      </td>
      {/* The sign is text; the colour is only an extra cue. */}
      <td role="cell" className={`${numericCell} font-semibold ${credit ? 'text-emerald-300' : 'text-rose-300'}`}>
        <span aria-hidden="true" className={cardLabel}>
          Amount
        </span>
        {formatSignedCoins(entry.amount)}
      </td>
      <td role="cell" className={`${numericCell} text-zinc-200`}>
        <span aria-hidden="true" className={cardLabel}>
          Balance after
        </span>
        <Coins amount={entry.balanceAfter} />
      </td>
    </tr>
  )
}

/** What the polite live region says, so loading, empty and appended pages are announced. */
function statusText(state: HistoryState): string {
  switch (state.status) {
    case 'loading':
      return 'Loading your transaction history…'
    case 'loaded': {
      if (state.refresh === 'refreshing') return 'Refreshing your transaction history…'
      if (state.older === 'loading') return 'Loading older entries…'
      const count = state.items.length
      if (count === 0) return 'No entries yet.'
      const entries = `${count.toLocaleString('en-GB')} ${count === 1 ? 'entry' : 'entries'}`
      return state.nextCursor === null
        ? `Showing ${entries}: your complete history.`
        : `Showing the ${entries} loaded so far. Older entries are available.`
    }
    default:
      return ''
  }
}

/**
 * The wallet history (§6.2): one `<table>` with a caption. On wide screens a table; on narrow screens
 * each row is a card through CSS only, so screen readers get the same table in both layouts and no
 * duplicate content (the card labels are `aria-hidden`; the explicit ARIA table roles keep the table
 * semantics when CSS changes the display of the table elements). Presentational: the state and the
 * actions come from `useWalletTransactions`.
 */
export function TransactionHistory({
  state,
  onLoadOlder,
  onRefresh,
}: {
  state: HistoryState
  onLoadOlder: () => void
  onRefresh: () => void
}) {
  const headingId = useId()
  const table = useRef<HTMLTableElement>(null)
  const olderTryAgain = useRef<HTMLButtonElement>(null)
  // The number of entries when "Load older entries" was activated, until its page has answered.
  const olderFrom = useRef<number | null>(null)

  // On the last page "Load older entries" disappears and a failure replaces it, so the focus would fall
  // back to the page (WCAG 2.4.3): it moves to the first new entry, or to the alert's "Try again".
  useEffect(() => {
    const from = olderFrom.current
    if (from === null || state.status !== 'loaded' || state.older === 'loading') return
    olderFrom.current = null
    if (state.older === 'failed') {
      olderTryAgain.current?.focus()
    } else if (state.nextCursor === null && state.items.length > from) {
      const row = table.current?.querySelectorAll<HTMLTableRowElement>('tbody tr')[from]
      if (row) {
        row.tabIndex = -1
        row.focus()
      }
    }
  }, [state])

  if (state.status === 'inactive') return null

  const loaded = state.status === 'loaded' ? state : null
  const refreshing = loaded?.refresh === 'refreshing'
  const busy = state.status === 'loading' || refreshing || loaded?.older === 'loading'
  const loadOlder = () => {
    olderFrom.current = loaded?.items.length ?? null
    onLoadOlder()
  }

  return (
    <section aria-labelledby={headingId} aria-busy={busy} className={card}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold text-zinc-100">
          Transaction history
        </h2>
        {loaded && (
          <button type="button" onClick={onRefresh} disabled={refreshing} className={`${secondaryButton} ${compact}`}>
            {refreshing ? 'Refreshing…' : 'Refresh history'}
          </button>
        )}
      </div>
      <p className="mt-1 text-sm text-zinc-400">
        Every change to your virtual balance, newest first, as recorded by the server.
      </p>

      <p role="status" className="mt-4 text-sm text-zinc-300 empty:hidden">
        {statusText(state)}
      </p>

      {state.status === 'loading' && (
        <div aria-hidden="true" className="mt-4 space-y-3">
          {[0, 1, 2].map((row) => (
            <div key={row} className="h-10 animate-pulse rounded-lg bg-zinc-800" />
          ))}
        </div>
      )}

      {state.status === 'failed' && (
        <div className="mt-4 space-y-3">
          <FormAlert tone="error">{state.message}</FormAlert>
          <button type="button" onClick={onRefresh} className={`${secondaryButton} ${compact}`}>
            Try again
          </button>
        </div>
      )}

      {loaded?.refresh === 'failed' && (
        <div className="mt-4">
          <FormAlert tone="error">
            Your transaction history could not be refreshed, so it may be out of date.{' '}
            <button type="button" onClick={onRefresh} className="font-semibold underline underline-offset-2">
              Try again
            </button>
          </FormAlert>
        </div>
      )}

      {loaded && loaded.items.length > 0 && (
        <table ref={table} role="table" className="mt-4 w-full text-left text-sm max-md:block">
          <caption className="sr-only">Wallet entries, newest first</caption>
          <thead role="rowgroup" className="max-md:sr-only">
            <tr role="row" className="text-xs text-zinc-400">
              <th role="columnheader" scope="col" className="pb-2 pr-4 font-medium">
                Time
              </th>
              <th role="columnheader" scope="col" className="pb-2 pr-4 font-medium">
                Type
              </th>
              <th role="columnheader" scope="col" className="pb-2 pr-4 text-right font-medium">
                Amount
              </th>
              <th role="columnheader" scope="col" className="pb-2 text-right font-medium">
                Balance after
              </th>
            </tr>
          </thead>
          <tbody role="rowgroup" className="max-md:block max-md:space-y-3">
            {loaded.items.map((entry) => (
              <EntryRow key={entry.id} entry={entry} />
            ))}
          </tbody>
        </table>
      )}

      {loaded?.older === 'failed' && (
        <div className="mt-4">
          <FormAlert tone="error">
            {loaded.error} The entries above are still shown.{' '}
            <button ref={olderTryAgain} type="button" onClick={loadOlder} className="font-semibold underline underline-offset-2">
              Try again
            </button>
          </FormAlert>
        </div>
      )}

      {loaded && loaded.nextCursor !== null && loaded.older !== 'failed' && (
        <div className="mt-4">
          <button
            type="button"
            onClick={loadOlder}
            disabled={loaded.older === 'loading' || refreshing}
            className={`${secondaryButton} ${touchTarget} w-full sm:w-auto`}
          >
            {loaded.older === 'loading' ? 'Loading older entries…' : 'Load older entries'}
          </button>
        </div>
      )}
    </section>
  )
}
