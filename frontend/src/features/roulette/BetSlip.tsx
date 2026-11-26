import { useEffect, useId, useMemo, useRef } from 'react'
import { primaryButton, secondaryButton, touchTarget } from '../../components/buttonStyles'
import { card } from '../../components/cardStyles'
import { ROULETTE_LIMITS } from '../../types/roulette'
import type { WalletState } from '../wallet/walletContext'
import { BetRows, Totals } from './BetRows'
import { blockReason, knownBalance, type ReviewBlock, type SlipBet, type SlipFeedback } from './betSlipReducer'
import { formatCoins } from './rouletteTable'
import { BalanceLine, SpinConfirmation } from './SpinConfirmation'
import { UnresolvedSpin } from './UnresolvedSpin'
import type { SpinState } from './useSpin'

interface BetSlipProps {
  bets: readonly SlipBet[]
  feedback: SlipFeedback | null
  wallet: WalletState
  block: ReviewBlock
  /** Id of the visible reason; the summary bar's "Review bets" refers to it as well. */
  reasonId: string
  spin: SpinState
  onRemove: (key: string) => void
  onClear: () => void
  onReview: () => void
  onBack: () => void
  onConfirm: () => void
  onRetry: () => void
  onCheckBalance: () => void
}

type Mode = 'editing' | 'confirming' | 'unresolved'

function modeOf(spin: SpinState): Mode {
  switch (spin.phase.kind) {
    case 'confirming':
      return 'confirming'
    case 'submitting':
      return spin.phase.retry ? 'unresolved' : 'confirming'
    case 'unresolved':
      return 'unresolved'
    default:
      return 'editing'
  }
}

/**
 * The bet slip (§5.5) and the spin steps that happen in it:
 *
 * - **editing:** each bet with amount and odds, the number of bets, the total stake, the shared
 *   balance, "Review bets" (`aria-disabled` with a visible reason when the slip is empty, the known
 *   balance is lower than the total or roulette is unavailable; an unknown balance does not block) and
 *   "Clear all bets". A definitive spin failure is shown here, with the server's messages on their rows;
 * - **confirming** (also while the request is in flight): `SpinConfirmation`;
 * - **unresolved** (also while a retry is in flight): `UnresolvedSpin`.
 *
 * Focus: the step heading when the confirmation or an unresolved spin appears, "Review bets" again on
 * "Back to bets", the error on a definitive failure. No potential win is shown anywhere (Q8).
 */
export function BetSlip({
  bets,
  feedback,
  wallet,
  block,
  reasonId,
  spin,
  onRemove,
  onClear,
  onReview,
  onBack,
  onConfirm,
  onRetry,
  onCheckBalance,
}: BetSlipProps) {
  const headingId = useId()
  const stepHeading = useRef<HTMLHeadingElement>(null)
  const reviewButton = useRef<HTMLButtonElement>(null)
  const errorRef = useRef<HTMLDivElement>(null)
  const previousPhase = useRef(spin.phase.kind)
  const sectionRef = useRef<HTMLElement>(null)
  // Where the focus goes after a removal: the next (or previous) row's remove button, or "Review bets".
  const focusAfterChange = useRef<string | null>(null)
  const mode = modeOf(spin)
  const reason = blockReason(block, bets, knownBalance(wallet))
  const error = spin.error

  const phase = spin.phase.kind
  useEffect(() => {
    const previous = previousPhase.current
    previousPhase.current = phase
    if (phase === previous) return
    // The confirmation, or an unresolved spin, appeared; not on page load (the initial phase).
    if (phase === 'confirming' || phase === 'unresolved') stepHeading.current?.focus()
    // Only "Back to bets" returns to "Review bets"; a settled round focuses its result instead.
    else if (previous === 'confirming' && phase === 'editing') reviewButton.current?.focus()
  }, [phase])

  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  // The removed control is gone, so the focus would fall back to the page (WCAG 2.4.3).
  useEffect(() => {
    const target = focusAfterChange.current
    if (target === null) return
    focusAfterChange.current = null
    const row = target === 'review' ? null : sectionRef.current?.querySelector<HTMLButtonElement>(`[data-bet-key="${target}"]`)
    const next = row ?? reviewButton.current
    next?.focus()
  }, [bets])

  const remove = (key: string) => {
    const index = bets.findIndex((bet) => bet.key === key)
    focusAfterChange.current = (bets[index + 1] ?? bets[index - 1])?.key ?? 'review'
    onRemove(key)
  }
  const clear = () => {
    focusAfterChange.current = 'review'
    onClear()
  }

  const rowErrors = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const { betKey, message } of error?.fieldErrors ?? []) {
      if (betKey !== null) map.set(betKey, [...(map.get(betKey) ?? []), message])
    }
    return map
  }, [error])
  const generalErrors = (error?.fieldErrors ?? []).filter((entry) => entry.betKey === null)

  let content
  if (mode === 'confirming') {
    const submitting = spin.phase.kind === 'submitting'
    content = (
      <SpinConfirmation
        bets={bets}
        wallet={wallet}
        reason={submitting ? null : reason}
        reasonId={reasonId}
        submitting={submitting}
        headingRef={stepHeading}
        onConfirm={onConfirm}
        onBack={onBack}
      />
    )
  } else if (spin.phase.kind === 'unresolved' || spin.phase.kind === 'submitting') {
    content = (
      <UnresolvedSpin
        attempt={spin.phase.attempt}
        message={spin.phase.kind === 'unresolved' ? spin.phase.message : ''}
        retrying={spin.phase.kind === 'submitting'}
        headingRef={stepHeading}
        onRetry={onRetry}
        onCheckBalance={onCheckBalance}
      />
    )
  } else {
    content = (
      <div className="space-y-4">
        {error && (
          <div ref={errorRef} tabIndex={-1} className="focus:outline-none">
            <div
              role="alert"
              className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-3.5 py-3 text-sm leading-relaxed text-rose-200"
            >
              <p>{error.message}</p>
              {generalErrors.map(({ message }) => (
                <p key={message} className="mt-1">
                  {message}
                </p>
              ))}
            </div>
          </div>
        )}
        {bets.length === 0 ? (
          <p className="text-sm text-zinc-400">No bets yet. Choose a chip value, then a number or an outside bet.</p>
        ) : (
          <BetRows bets={bets} onRemove={remove} errors={rowErrors} />
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
            className={`${primaryButton} w-full max-md:scroll-mb-24 ${touchTarget} aria-disabled:cursor-not-allowed aria-disabled:opacity-50`}
          >
            Review bets
          </button>
          {reason && (
            <p id={reasonId} className="text-sm text-zinc-400">
              {reason}
            </p>
          )}
          {bets.length > 0 && (
            <button type="button" onClick={clear} className={`${secondaryButton} w-full max-md:scroll-mb-24 ${touchTarget}`}>
              Clear all bets
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <section ref={sectionRef} aria-labelledby={headingId} className={`${card} space-y-4 max-sm:p-4`}>
      <h2 id={headingId} className="text-lg font-semibold text-zinc-100">
        Bet slip
      </h2>

      {content}

      {/* One polite live region for every change and refusal; the key re-announces a repeated message. */}
      <p role="status" className="text-sm">
        {feedback && (
          <span key={feedback.id} className={feedback.tone === 'refused' ? 'text-amber-200' : 'text-zinc-300'}>
            {feedback.message}
          </span>
        )}
      </p>

      <p className="text-xs leading-relaxed text-zinc-400">
        Up to {ROULETTE_LIMITS.maxBets} different bets per spin, {formatCoins(ROULETTE_LIMITS.minAmount)} to{' '}
        {formatCoins(ROULETTE_LIMITS.maxAmount)} per bet, {formatCoins(ROULETTE_LIMITS.maxTotalStake)} in total.
      </p>
    </section>
  )
}
