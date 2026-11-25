import type { Ref } from 'react'
import { primaryButton, secondaryButton, touchTarget } from '../../components/buttonStyles'
import { FormAlert } from '../../components/form/FormAlert'
import { BetRows, Totals } from './BetRows'
import { slipFromRequest } from './betSlipReducer'
import type { PendingSpin } from './pendingSpinStore'

/**
 * A spin whose outcome is unknown (§8.3, §8.4): its frozen bets, why it is not confirmed, and the only
 * ways on: "Retry spin" (the same key and body; the server plays it once or returns the round it
 * already played) and "Check balance". Betting stays locked meanwhile, so no other spin can race with
 * it. Nothing here is sent without a click.
 */
export function UnresolvedSpin({
  attempt,
  message,
  retrying,
  headingRef,
  onRetry,
  onCheckBalance,
}: {
  attempt: PendingSpin
  message: string
  retrying: boolean
  headingRef: Ref<HTMLHeadingElement>
  onRetry: () => void
  onCheckBalance: () => void
}) {
  const bets = slipFromRequest(attempt.request)
  return (
    <div className="space-y-4" aria-busy={retrying}>
      <h3 ref={headingRef} tabIndex={-1} className="text-base font-semibold text-zinc-100 focus:outline-none">
        {retrying ? 'Waiting for the server' : 'Spin not confirmed'}
      </h3>
      {!retrying && <FormAlert tone="error">{message}</FormAlert>}
      <BetRows bets={bets} />
      <Totals bets={bets} />
      <p className="text-xs leading-relaxed text-zinc-400">
        Betting is paused until this spin is confirmed. A retry uses the same spin id and the same bets, so it is never
        charged twice. Your wallet history also shows whether it was played.
      </p>
      <div className="flex flex-col gap-2">
        <button
          type="button"
          onClick={() => {
            if (!retrying) onRetry()
          }}
          aria-disabled={retrying ? true : undefined}
          className={`${primaryButton} w-full max-md:scroll-mb-24 ${touchTarget} aria-disabled:cursor-not-allowed aria-disabled:opacity-60`}
        >
          {retrying ? 'Waiting for the server…' : 'Retry spin'}
        </button>
        <button type="button" onClick={onCheckBalance} className={`${secondaryButton} w-full max-md:scroll-mb-24 ${touchTarget}`}>
          Check balance
        </button>
      </div>
    </div>
  )
}
