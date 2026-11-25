import { useEffect, useId, useRef } from 'react'
import { card } from '../../components/cardStyles'
import { Coins } from '../../components/Coins'
import type { RouletteNumberColor, RouletteSpinResult } from '../../types/roulette'
import { formatEntryTime, formatSignedCoins } from '../wallet/walletFormat'
import { cellFor, formatCoins } from './rouletteTable'

const COLOR_NAME: Record<RouletteNumberColor, string> = { RED: 'Red', BLACK: 'Black', GREEN: 'Green' }

const badgeColor: Record<RouletteNumberColor, string> = {
  RED: 'bg-rose-700',
  BLACK: 'bg-zinc-900 ring-1 ring-zinc-600',
  GREEN: 'bg-emerald-700',
}

/**
 * The server's result of one round (§5.7), rendered **only** from the `RouletteSpinResult`: the winning
 * number and the server's colour, every bet in request order with Won/Lost (from `payout > 0`) and its
 * payout, the totals, the net result (`totalPayout − totalStake`: the contract's definition, arithmetic
 * on two server values) and the balance right after the round. Wins and losses share one layout and
 * weight; there is no celebration and no "repeat bet". Focus moves to the heading when a new round is
 * shown.
 */
export function SpinResult({ result }: { result: RouletteSpinResult }) {
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const net = result.totalPayout - result.totalStake
  const color = COLOR_NAME[result.winningColor]

  useEffect(() => {
    heading.current?.focus()
  }, [result.roundId])

  return (
    <section aria-labelledby={headingId} className={`${card} space-y-4 max-sm:p-4`}>
      <h2 id={headingId} ref={heading} tabIndex={-1} className="text-lg font-semibold text-zinc-100 focus:outline-none">
        Result
      </h2>
      <div className="flex items-center gap-4">
        <span
          aria-hidden="true"
          className={`flex size-14 shrink-0 items-center justify-center rounded-full text-2xl font-bold text-white tabular-nums ${badgeColor[result.winningColor]}`}
        >
          {result.winningNumber}
        </span>
        <p role="status" className="text-sm leading-relaxed text-zinc-200">
          <span className="block text-base font-semibold text-zinc-50">
            {result.winningNumber} · {color}
          </span>
          Staked {formatCoins(result.totalStake)}, returned {formatCoins(result.totalPayout)}, net{' '}
          {formatSignedCoins(net)}.
        </p>
      </div>

      <ul aria-label="Your bets in this round" className="divide-y divide-zinc-800 rounded-lg border border-zinc-800">
        {result.bets.map((bet, index) => {
          const entry = cellFor(bet)
          return (
            <li key={`${entry.key}-${index}`} className="flex items-center gap-3 px-3 py-2 text-sm">
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium text-zinc-100">{entry.name}</p>
                <p className="text-xs text-zinc-400">Stake {formatCoins(bet.amount)}</p>
              </div>
              <div className="shrink-0 text-right">
                <p className="text-xs font-semibold uppercase tracking-wide text-zinc-300">{bet.payout > 0 ? 'Won' : 'Lost'}</p>
                <p className="text-xs text-zinc-400">Payout {formatCoins(bet.payout)}</p>
              </div>
            </li>
          )
        })}
      </ul>

      <dl className="grid grid-cols-2 gap-y-1 text-sm">
        <dt className="text-zinc-400">Total stake</dt>
        <dd className="text-right text-zinc-200">
          <Coins amount={result.totalStake} />
        </dd>
        <dt className="text-zinc-400">Total payout</dt>
        <dd className="text-right text-zinc-200">
          <Coins amount={result.totalPayout} />
        </dd>
        <dt className="text-zinc-400">Net result</dt>
        <dd className="text-right tabular-nums text-zinc-200">{formatSignedCoins(net)}</dd>
        <dt className="text-zinc-400">Balance after this round</dt>
        <dd className="text-right font-semibold text-zinc-50">
          <Coins amount={result.balance} />
        </dd>
      </dl>

      <p className="text-xs text-zinc-500">
        Played <time dateTime={result.createdAt}>{formatEntryTime(result.createdAt)}</time> · round{' '}
        <span className="font-mono">{result.roundId.slice(0, 8)}</span>. Drawn and settled by the server.
      </p>
    </section>
  )
}
