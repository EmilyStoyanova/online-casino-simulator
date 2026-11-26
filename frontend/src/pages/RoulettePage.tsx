import { useCallback, useId, useMemo, useReducer, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { card } from '../components/cardStyles'
import { PageHeader } from '../components/layout/PageHeader'
import { useAuth } from '../features/auth/useAuth'
import { BetSlip } from '../features/roulette/BetSlip'
import { BetSummaryBar } from '../features/roulette/BetSummaryBar'
import { ChipSelector } from '../features/roulette/ChipSelector'
import { OddsInfo } from '../features/roulette/OddsInfo'
import { RouletteBoard } from '../features/roulette/RouletteBoard'
import { RouletteWheel, type RouletteWheelHandle } from '../features/roulette/RouletteWheel'
import { SpinResult } from '../features/roulette/SpinResult'
import {
  betSlipReducer,
  DEFAULT_CHIP,
  EMPTY_BET_SLIP,
  knownBalance,
  reviewBlock,
  type BetSlipAction,
  type ChipValue,
  type ReviewBlock,
} from '../features/roulette/betSlipReducer'
import { useRouletteAvailability } from '../features/roulette/rouletteAvailability'
import type { BetCell } from '../features/roulette/rouletteTable'
import { useSpin, type SpinPhase } from '../features/roulette/useSpin'
import { BalanceCard } from '../features/wallet/BalanceCard'
import { useWallet } from '../features/wallet/useWallet'
import type { MessageKey } from '../i18n/translate'
import { useI18n } from '../i18n/useI18n'

const LOCK_REASON: Partial<Record<SpinPhase['kind'], MessageKey>> = {
  confirming: 'roulette.lock.confirming',
  submitting: 'roulette.lock.submitting',
  revealing: 'roulette.lock.revealing',
  unresolved: 'roulette.lock.unresolved',
}

function Unavailable() {
  const { t, rich } = useI18n()
  return (
    <div role="status" className="rounded-lg border border-amber-400/40 bg-amber-400/10 px-3.5 py-3 text-sm leading-relaxed text-amber-100">
      <p className="font-semibold">{t('roulette.unavailable')}</p>
      <p className="mt-1">
        {rich('roulette.unavailableText', {
          link: (
            <Link to="/wallet" className="font-semibold underline underline-offset-2">
              {t('roulette.walletLink')}
            </Link>
          ),
        })}
      </p>
    </div>
  )
}

function ResultArea({ phase, children }: { phase: SpinPhase; children: ReactNode }) {
  const { t } = useI18n()
  if (phase.kind === 'revealing') {
    return (
      <section aria-labelledby="roulette-result-heading" aria-busy="true" className={`${card} max-sm:p-4`}>
        <h2 id="roulette-result-heading" className="text-lg font-semibold text-zinc-100">
          {t('result.heading')}
        </h2>
        <p role="status" className="mt-2 text-sm text-zinc-300">
          {t('result.revealing')}
        </p>
      </section>
    )
  }
  return children
}

/**
 * The table of one player (§9.1): chip value and bet slip in local state, the spin attempt (`useSpin`),
 * the board, the wheel, the result, the shared balance and the slip.
 *
 * A spin needs "Review bets" and then "Confirm and spin". The server draws the number, computes every
 * payout and the balance; the page shows its result after the wheel turned to the server's number.
 * React owns all state and the request; Phaser only draws the wheel and is told the server's number.
 */
function RouletteTable({ userId }: { userId: string }) {
  const [slip, dispatch] = useReducer(betSlipReducer, EMPTY_BET_SLIP)
  const [chip, setChip] = useState<ChipValue>(DEFAULT_CHIP)
  const { t } = useI18n()
  const { state: wallet } = useWallet()
  const availability = useRouletteAvailability()
  const wheel = useRef<RouletteWheelHandle>(null)
  const reasonId = useId()
  const onSettled = useCallback(() => dispatch({ type: 'reset' }), [])
  const spin = useSpin({ userId, wheel, onSettled })

  const unavailable = availability.status === 'unavailable'
  const phase = spin.state.phase
  const block: ReviewBlock = unavailable ? 'unavailable' : reviewBlock(slip.bets, knownBalance(wallet))
  const lockKey = unavailable ? 'roulette.lock.unavailable' : LOCK_REASON[phase.kind]
  const lockedReason = lockKey ? t(lockKey) : null
  const amounts = useMemo(() => new Map(slip.bets.map((bet) => [bet.key, bet.amount])), [slip.bets])

  const change = (action: BetSlipAction) => {
    dispatch(action)
    spin.dismissError()
  }
  const review = () => {
    if (block === null && phase.kind === 'editing') spin.review()
  }

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <section aria-labelledby="roulette-table-heading" className={`${card} min-w-0 space-y-5 max-sm:p-4`}>
          <div className="space-y-2">
            <h2 id="roulette-table-heading" className="text-lg font-semibold text-zinc-100">
              {t('roulette.placeBets')}
            </h2>
            {unavailable ? <Unavailable /> : <p className="text-sm leading-relaxed text-zinc-300">{t('roulette.howTo')}</p>}
          </div>
          <ChipSelector value={chip} onChange={setChip} />
          <RouletteBoard
            amounts={amounts}
            onPlace={(cell: BetCell) => change({ type: 'add', selection: cell.selection, amount: chip })}
            lockedReason={lockedReason}
          />
          <OddsInfo />
        </section>

        <div className="space-y-6">
          <section aria-labelledby="roulette-wheel-heading" className={`${card} max-sm:p-4`}>
            <h2 id="roulette-wheel-heading" className="mb-4 text-lg font-semibold text-zinc-100">
              {t('wheel.heading')}
            </h2>
            <RouletteWheel ref={wheel} />
            <p className="mt-4 text-center text-xs text-zinc-400">{t('wheel.note')}</p>
          </section>
          <ResultArea phase={phase}>{spin.state.result && <SpinResult result={spin.state.result} />}</ResultArea>
          <BalanceCard />
          <BetSlip
            bets={slip.bets}
            feedback={slip.feedback}
            wallet={wallet}
            block={block}
            reasonId={reasonId}
            spin={spin.state}
            onRemove={(key) => change({ type: 'remove', key })}
            onClear={() => change({ type: 'clear' })}
            onReview={review}
            onBack={spin.back}
            onConfirm={() => spin.confirm(slip.bets)}
            onRetry={spin.retry}
            onCheckBalance={spin.checkBalance}
          />
        </div>
      </div>
      <BetSummaryBar
        bets={slip.bets}
        feedback={slip.feedback}
        block={block}
        reasonId={reasonId}
        phase={phase.kind}
        onReview={review}
      />
    </div>
  )
}

/**
 * `/roulette`, inside `RequireAuth` and `PlayerOnly`: betting table, chips, bet slip, the spin flow
 * (W5-07), the Phaser wheel and the shared server-confirmed balance. Opening the page sends no game
 * request; a spin is sent only after "Review bets" and "Confirm and spin", and an unresolved one only
 * after "Retry spin".
 *
 * The table is keyed by the player, so local bets and the spin state never carry over to another
 * account (the page also unmounts on sign-out and on an expired session).
 */
export function RoulettePage() {
  const { state } = useAuth()
  const { t } = useI18n()
  const player = state.status === 'authenticated' ? state.user.id : null

  return (
    <div className="space-y-6">
      <PageHeader title={t('roulette.title')}>{t('roulette.intro')}</PageHeader>
      {player && <RouletteTable key={player} userId={player} />}
    </div>
  )
}
