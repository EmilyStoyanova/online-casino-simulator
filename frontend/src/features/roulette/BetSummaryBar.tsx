import { primaryButton, touchTarget } from '../../components/buttonStyles'
import { Coins } from '../../components/Coins'
import type { MessageKey } from '../../i18n/translate'
import { useI18n } from '../../i18n/useI18n'
import { totalStake, type ReviewBlock, type SlipBet, type SlipFeedback } from './betSlipReducer'
import type { SpinPhase } from './useSpin'

interface BetSummaryBarProps {
  bets: readonly SlipBet[]
  feedback: SlipFeedback | null
  block: ReviewBlock
  /** Id of the bet slip's visible reason, so this button is described the same way. */
  reasonId: string
  phase: SpinPhase['kind']
  onReview: () => void
}

const PHASE_TEXT: Partial<Record<SpinPhase['kind'], MessageKey>> = {
  confirming: 'bar.confirming',
  submitting: 'common.waitingForServer',
  revealing: 'bar.revealing',
  unresolved: 'spin.notConfirmed',
}

/**
 * Below `md` the bet slip is far below the table, so a bar sticks to the bottom of the page while bets
 * exist (§5.2): "2 bets · 15 coins" and "Review bets", or the state of the spin. It repeats the slip's
 * last message for sighted players (`aria-hidden`: the slip's live region already announces it).
 */
export function BetSummaryBar({ bets, feedback, block, reasonId, phase, onReview }: BetSummaryBarProps) {
  const { t } = useI18n()
  if (bets.length === 0) return null
  const text = PHASE_TEXT[phase]

  return (
    <div className="sticky bottom-0 z-20 -mx-4 border-t border-zinc-800 bg-zinc-950/95 px-4 py-3 shadow-[0_-8px_24px_rgba(0,0,0,0.45)] backdrop-blur md:hidden">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-zinc-200">
          {t('common.bets', { count: bets.length })} · <Coins amount={totalStake(bets)} className="font-semibold" />
        </p>
        {text ? (
          <p className="text-sm text-zinc-400">{t(text)}</p>
        ) : (
          <button
            type="button"
            onClick={onReview}
            aria-disabled={block !== null ? true : undefined}
            aria-describedby={block !== null ? reasonId : undefined}
            className={`${primaryButton} ${touchTarget} aria-disabled:cursor-not-allowed aria-disabled:opacity-50`}
          >
            {t('slip.review')}
          </button>
        )}
      </div>
      {feedback && (
        <p aria-hidden="true" className={`mt-1 text-xs ${feedback.tone === 'refused' ? 'text-amber-200' : 'text-zinc-400'}`}>
          {t(feedback.message)}
        </p>
      )}
    </div>
  )
}
