import { Coins } from '../../components/Coins'
import { ROULETTE_LIMITS } from '../../types/roulette'
import { totalStake, type SlipBet } from './betSlipReducer'
import { cellFor, formatCoins } from './rouletteTable'

/**
 * The bets of the slip, of the confirmation or of an unresolved attempt: name, bet type and odds,
 * amount, and (only while editing) a remove button. A server validation message is shown under its row.
 */
export function BetRows({
  bets,
  onRemove,
  errors,
}: {
  bets: readonly SlipBet[]
  onRemove?: (key: string) => void
  errors?: ReadonlyMap<string, readonly string[]>
}) {
  return (
    <ul aria-label="Your bets" className="divide-y divide-zinc-800 rounded-lg border border-zinc-800">
      {bets.map((bet) => {
        const entry = cellFor(bet.selection)
        const messages = errors?.get(bet.key) ?? []
        return (
          <li key={bet.key} className="px-3 py-2">
            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-zinc-100">{entry.name}</p>
                <p className="text-xs text-zinc-400">
                  {entry.betType} · <span className="whitespace-nowrap">pays {entry.odds}</span>
                </p>
              </div>
              <Coins amount={bet.amount} className="shrink-0 text-sm font-semibold text-zinc-100" />
              {onRemove && (
                <button
                  type="button"
                  onClick={() => onRemove(bet.key)}
                  aria-label={`Remove ${entry.name}, ${formatCoins(bet.amount)}`}
                  className="-mr-1 flex size-11 shrink-0 items-center justify-center rounded-lg text-zinc-400 transition hover:bg-zinc-800 hover:text-zinc-100 max-md:scroll-mb-24 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400"
                >
                  <span aria-hidden="true" className="text-lg leading-none">
                    ✕
                  </span>
                </button>
              )}
            </div>
            {messages.map((message) => (
              <p key={message} className="mt-1 text-xs text-rose-300">
                {message}
              </p>
            ))}
          </li>
        )
      })}
    </ul>
  )
}

/** "Bets n of 10" and "Total stake": the only sum the client computes. */
export function Totals({ bets }: { bets: readonly SlipBet[] }) {
  return (
    <dl className="grid grid-cols-2 gap-y-1 text-sm">
      <dt className="text-zinc-400">Bets</dt>
      <dd className="text-right tabular-nums text-zinc-200">
        {bets.length} of {ROULETTE_LIMITS.maxBets}
      </dd>
      <dt className="text-zinc-400">Total stake</dt>
      <dd className="text-right font-semibold text-zinc-50">
        <Coins amount={totalStake(bets)} />
      </dd>
    </dl>
  )
}
