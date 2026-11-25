import { useEffect, useId, useRef } from 'react'
import { primaryButton, secondaryButton } from '../../components/buttonStyles'
import { card } from '../../components/cardStyles'
import { Coins } from '../../components/Coins'
import { ROULETTE_LIMITS } from '../../types/roulette'
import type { WalletState } from '../wallet/walletContext'
import { blockReason, knownBalance, totalStake, type ReviewBlock, type SlipBet, type SlipFeedback } from './betSlipReducer'
import { cellFor, formatCoins } from './rouletteTable'

function BalanceLine({ wallet }: { wallet: WalletState }) {
  if (wallet.status === 'loaded') {
    return (
      <p className="text-sm text-zinc-300">
        Your balance: <Coins amount={wallet.balance} className="font-semibold text-zinc-100" />
        {wallet.refresh === 'failed' && <span className="text-zinc-400"> (may be out of date)</span>}
      </p>
    )
  }
  if (wallet.status === 'loading') return <p className="text-sm text-zinc-400">Checking your balance…</p>
  return (
    <p className="text-sm text-zinc-400">
      Your balance is not known right now. The server checks it when a spin is sent.
    </p>
  )
}

function BetRows({ bets, onRemove }: { bets: readonly SlipBet[]; onRemove?: (key: string) => void }) {
  return (
    <ul aria-label="Your bets" className="divide-y divide-zinc-800 rounded-lg border border-zinc-800">
      {bets.map((bet) => {
        const entry = cellFor(bet.selection)
        return (
          <li key={bet.key} className="flex items-center gap-3 px-3 py-2">
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
          </li>
        )
      })}
    </ul>
  )
}

function Totals({ bets }: { bets: readonly SlipBet[] }) {
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

interface BetSlipProps {
  bets: readonly SlipBet[]
  feedback: SlipFeedback | null
  wallet: WalletState
  block: ReviewBlock
  /** Id of the visible reason; the summary bar's "Review bets" refers to it as well. */
  reasonId: string
  reviewing: boolean
  onRemove: (key: string) => void
  onClear: () => void
  onReview: () => void
  onBack: () => void
}

/**
 * The bet slip (§5.5): each bet with its amount and odds, the number of bets and the total stake, the
 * shared server-confirmed balance, "Review bets" and "Clear all bets". "Review bets" is `aria-disabled`
 * with a visible reason (its description) when the slip is empty or the known balance is lower than
 * the total; an unknown balance does not block (the server decides).
 *
 * The review is read-only (W5-06): the bets, the total and the balance, and an honest note that spins
 * are not switched on yet. There is no "Confirm and spin" button; W5-07 adds it. Focus moves to the
 * review heading and, with "Back to bets", back to "Review bets". No potential win is shown anywhere:
 * the odds are enough, and a payout preview would be a client-side payout calculation (Q8).
 */
export function BetSlip({ bets, feedback, wallet, block, reasonId, reviewing, onRemove, onClear, onReview, onBack }: BetSlipProps) {
  const headingId = useId()
  const reviewHeading = useRef<HTMLHeadingElement>(null)
  const reviewButton = useRef<HTMLButtonElement>(null)
  const wasReviewing = useRef(false)
  const reason = blockReason(block, bets, knownBalance(wallet))

  useEffect(() => {
    if (reviewing) reviewHeading.current?.focus()
    else if (wasReviewing.current) reviewButton.current?.focus()
    wasReviewing.current = reviewing
  }, [reviewing])

  return (
    <section aria-labelledby={headingId} className={`${card} space-y-4 max-sm:p-4`}>
      <div className="flex items-baseline justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold text-zinc-100">
          Bet slip
        </h2>
        <span className="text-xs text-zinc-400">Nothing is sent yet</span>
      </div>

      {reviewing ? (
        <div className="space-y-4">
          <h3 ref={reviewHeading} tabIndex={-1} className="text-base font-semibold text-zinc-100 focus:outline-none">
            Review your bets
          </h3>
          <BetRows bets={bets} />
          <Totals bets={bets} />
          <BalanceLine wallet={wallet} />
          {reason && (
            <p id={reasonId} className="text-sm text-amber-200">
              {reason}
            </p>
          )}
          <p className="rounded-lg border border-sky-500/40 bg-sky-500/10 px-3.5 py-3 text-sm leading-relaxed text-sky-100">
            Spinning is not switched on yet: it arrives with the next development step (W5-07). Nothing has been
            sent to the server and none of your coins have been used.
          </p>
          <button type="button" onClick={onBack} className={`${secondaryButton} w-full max-md:scroll-mb-24`}>
            Back to bets
          </button>
        </div>
      ) : (
        <div className="space-y-4">
          {bets.length === 0 ? (
            <p className="text-sm text-zinc-400">No bets yet. Choose a chip value, then a number or an outside bet.</p>
          ) : (
            <BetRows bets={bets} onRemove={onRemove} />
          )}
          <Totals bets={bets} />
          <BalanceLine wallet={wallet} />
          <div className="flex flex-col gap-2">
            <button
              ref={reviewButton}
              type="button"
              onClick={onReview}
              aria-disabled={block !== null ? true : undefined}
              aria-describedby={reason ? reasonId : undefined}
              className={`${primaryButton} w-full max-md:scroll-mb-24 aria-disabled:cursor-not-allowed aria-disabled:opacity-50`}
            >
              Review bets
            </button>
            {reason && (
              <p id={reasonId} className="text-sm text-zinc-400">
                {reason}
              </p>
            )}
            {bets.length > 0 && (
              <button type="button" onClick={onClear} className={`${secondaryButton} w-full max-md:scroll-mb-24`}>
                Clear all bets
              </button>
            )}
          </div>
        </div>
      )}

      {/* One polite live region for every change and refusal; the key re-announces a repeated message. */}
      <p role="status" className="text-sm">
        {feedback && (
          <span key={feedback.id} className={feedback.tone === 'refused' ? 'text-amber-200' : 'text-zinc-300'}>
            {feedback.message}
          </span>
        )}
      </p>

      <p className="text-xs leading-relaxed text-zinc-500">
        Up to {ROULETTE_LIMITS.maxBets} different bets per spin, {formatCoins(ROULETTE_LIMITS.minAmount)} to{' '}
        {formatCoins(ROULETTE_LIMITS.maxAmount)} per bet, {formatCoins(ROULETTE_LIMITS.maxTotalStake)} in total.
      </p>
    </section>
  )
}
