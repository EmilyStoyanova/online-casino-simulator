import { useId, useMemo, useReducer, useState } from 'react'
import { card } from '../components/cardStyles'
import { PageHeader } from '../components/layout/PageHeader'
import { useAuth } from '../features/auth/useAuth'
import { BetSlip } from '../features/roulette/BetSlip'
import { BetSummaryBar } from '../features/roulette/BetSummaryBar'
import { ChipSelector } from '../features/roulette/ChipSelector'
import { RouletteBoard } from '../features/roulette/RouletteBoard'
import { RouletteWheel } from '../features/roulette/RouletteWheel'
import {
  betSlipReducer,
  DEFAULT_CHIP,
  EMPTY_BET_SLIP,
  knownBalance,
  reviewBlock,
  type ChipValue,
} from '../features/roulette/betSlipReducer'
import type { BetCell } from '../features/roulette/rouletteTable'
import { BalanceCard } from '../features/wallet/BalanceCard'
import { useWallet } from '../features/wallet/useWallet'

const LOCKED_WHILE_REVIEWING = 'Your bets are being reviewed. Choose “Back to bets” in the bet slip to change them.'

/**
 * The table of one player: chip value and bet slip in local state (§9.1), the board, the wheel, the
 * shared balance and the slip. Nothing here is sent to the server in W5-06: there is no spin request
 * and no wallet change; the wheel stands still.
 */
function RouletteTable() {
  const [slip, dispatch] = useReducer(betSlipReducer, EMPTY_BET_SLIP)
  const [chip, setChip] = useState<ChipValue>(DEFAULT_CHIP)
  const [reviewing, setReviewing] = useState(false)
  const { state: wallet } = useWallet()
  const reasonId = useId()

  const block = reviewBlock(slip.bets, knownBalance(wallet))
  const amounts = useMemo(() => new Map(slip.bets.map((bet) => [bet.key, bet.amount])), [slip.bets])
  const place = (cell: BetCell) => dispatch({ type: 'add', selection: cell.selection, amount: chip })
  const review = () => {
    if (block === null) setReviewing(true)
  }

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <section aria-labelledby="roulette-table-heading" className={`${card} min-w-0 space-y-5 max-sm:p-4`}>
          <div className="space-y-2">
            <h2 id="roulette-table-heading" className="text-lg font-semibold text-zinc-100">
              Place your bets
            </h2>
            <p className="text-sm leading-relaxed text-zinc-300">
              Spinning is not switched on yet: it arrives with the next development step (W5-07). You can place
              and review bets now; nothing is sent to the server and none of your coins are used.
            </p>
          </div>
          <ChipSelector value={chip} onChange={setChip} />
          <RouletteBoard amounts={amounts} onPlace={place} lockedReason={reviewing ? LOCKED_WHILE_REVIEWING : null} />
        </section>

        <div className="space-y-6">
          <section aria-labelledby="roulette-wheel-heading" className={`${card} max-sm:p-4`}>
            <h2 id="roulette-wheel-heading" className="mb-4 text-lg font-semibold text-zinc-100">
              The wheel
            </h2>
            <RouletteWheel />
            <p className="mt-4 text-center text-xs text-zinc-500">
              The wheel stands still. It will turn only to show a number drawn by the server.
            </p>
          </section>
          <BalanceCard />
          <BetSlip
            bets={slip.bets}
            feedback={slip.feedback}
            wallet={wallet}
            block={block}
            reasonId={reasonId}
            reviewing={reviewing}
            onRemove={(key) => dispatch({ type: 'remove', key })}
            onClear={() => dispatch({ type: 'clear' })}
            onReview={review}
            onBack={() => setReviewing(false)}
          />
        </div>
      </div>
      <BetSummaryBar
        bets={slip.bets}
        feedback={slip.feedback}
        block={block}
        reasonId={reasonId}
        reviewing={reviewing}
        onReview={review}
      />
    </div>
  )
}

/**
 * `/roulette`, inside `RequireAuth` and `PlayerOnly`: the betting table, chips and bet slip (W5-06),
 * the Phaser wheel (W5-P01, standing still) and the shared server-confirmed balance. React owns the
 * bets; Phaser only draws the wheel; the server will draw every number and compute every payout.
 *
 * The table is keyed by the player, so local bets never carry over to another account (the page also
 * unmounts on sign-out and on an expired session).
 */
export function RoulettePage() {
  const { state } = useAuth()
  const player = state.status === 'authenticated' ? state.user.id : 'none'

  return (
    <div className="space-y-6">
      <PageHeader title="European roulette">
        Single-zero roulette with virtual coins only. Every number is drawn by the server, never in your browser.
      </PageHeader>
      <RouletteTable key={player} />
    </div>
  )
}
