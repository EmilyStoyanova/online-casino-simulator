import {
  ROULETTE_LIMITS,
  type RouletteBet,
  type RouletteSelection,
  type RouletteSpinRequest,
} from '../../types/roulette'
import type { WalletState } from '../wallet/walletContext'
import { cellFor, formatCoins } from './rouletteTable'

/**
 * The local, unsubmitted bets of one round (§5.5): a pure reducer, its selectors and `toSpinRequest`.
 *
 * The limits mirror the contract for user feedback only. The server validates every request again,
 * checks the balance under its wallet lock and alone draws the number and computes payouts; nothing
 * here computes a win.
 */

/** The chip values (Q5), in coins. */
export const CHIP_VALUES = [1, 5, 10, 25, 100, 500] as const
export type ChipValue = (typeof CHIP_VALUES)[number]
export const DEFAULT_CHIP: ChipValue = 10

/** One selection of the slip with its accumulated amount; `key` is `selectionKey(selection)`. */
export interface SlipBet {
  readonly key: string
  readonly selection: RouletteSelection
  readonly amount: number
}

/**
 * The result of the last action, for the polite announcement and the visible note. `id` changes with
 * every action, so the same message (e.g. a second refusal) is announced again.
 */
export interface SlipFeedback {
  readonly id: number
  readonly tone: 'change' | 'refused'
  readonly message: string
}

export interface BetSlipState {
  /** In the order the selections were first placed (the order of the spin request). */
  readonly bets: readonly SlipBet[]
  readonly feedback: SlipFeedback | null
}

export type BetSlipAction =
  | { type: 'add'; selection: RouletteSelection; amount: number }
  | { type: 'remove'; key: string }
  | { type: 'clear' }
  /** After a round the server confirmed: the bets were played, so the slip starts empty (Q7). */
  | { type: 'reset' }

export interface SlipLimits {
  readonly maxBets: number
  readonly minAmount: number
  readonly maxAmount: number
  readonly maxTotalStake: number
}

export const EMPTY_BET_SLIP: BetSlipState = Object.freeze({ bets: Object.freeze([]), feedback: null })

/** The sum of the amounts: the only sum the client computes before a spin. */
export function totalStake(bets: readonly SlipBet[]): number {
  return bets.reduce((sum, bet) => sum + bet.amount, 0)
}

function summary(bets: readonly SlipBet[]): string {
  if (bets.length === 0) return 'No bets left.'
  return `${bets.length} ${bets.length === 1 ? 'bet' : 'bets'}, total ${formatCoins(totalStake(bets))}.`
}

function isWholeAmount(value: number, limits: SlipLimits): boolean {
  return Number.isSafeInteger(value) && value >= limits.minAmount && value <= limits.maxAmount
}

/**
 * A reducer for the given limits. The app uses `betSlipReducer` (the contract limits); tests pass
 * smaller limits to reach the total-stake rule, which 10 × 1,000 coins cannot exceed today.
 */
export function createBetSlipReducer(limits: SlipLimits = ROULETTE_LIMITS) {
  const maxTotal = formatCoins(limits.maxTotalStake)

  return function betSlipReducer(state: BetSlipState, action: BetSlipAction): BetSlipState {
    const nextId = (state.feedback?.id ?? 0) + 1
    const change = (bets: readonly SlipBet[], message: string): BetSlipState => ({
      bets,
      feedback: { id: nextId, tone: 'change', message },
    })
    const refuse = (message: string): BetSlipState => ({
      bets: state.bets,
      feedback: { id: nextId, tone: 'refused', message },
    })

    switch (action.type) {
      case 'add': {
        // Programming errors, not user input: the board offers only table cells and fixed chips.
        const entry = cellFor(action.selection)
        if (!isWholeAmount(action.amount, limits)) throw new RangeError(`Not a chip amount: ${action.amount}`)

        const total = totalStake(state.bets)
        const index = state.bets.findIndex((bet) => bet.key === entry.key)
        if (index === -1) {
          if (state.bets.length >= limits.maxBets) {
            return refuse(`Not added: up to ${limits.maxBets} different bets per spin. Remove a bet to choose ${entry.name}.`)
          }
          if (total + action.amount > limits.maxTotalStake) return refuse(`Not added: up to ${maxTotal} per spin.`)
          const bets = [...state.bets, { key: entry.key, selection: entry.selection, amount: action.amount }]
          return change(bets, `${entry.name}: ${formatCoins(action.amount)}. ${summary(bets)}`)
        }

        const current = state.bets[index] as SlipBet
        if (current.amount >= limits.maxAmount) {
          return refuse(`Not added: ${entry.name} already has ${formatCoins(limits.maxAmount)}, the maximum per bet.`)
        }
        const amount = Math.min(current.amount + action.amount, limits.maxAmount)
        if (total - current.amount + amount > limits.maxTotalStake) return refuse(`Not added: up to ${maxTotal} per spin.`)
        const bets = state.bets.map((bet, position) => (position === index ? { ...bet, amount } : bet))
        const capped = amount < current.amount + action.amount ? ', the maximum per bet' : ''
        return change(bets, `${entry.name}: ${formatCoins(amount)}${capped}. ${summary(bets)}`)
      }
      case 'remove': {
        const removed = state.bets.find((bet) => bet.key === action.key)
        if (!removed) return state
        const bets = state.bets.filter((bet) => bet !== removed)
        return change(bets, `${cellFor(removed.selection).name} removed. ${summary(bets)}`)
      }
      case 'clear':
        return state.bets.length === 0 ? state : change([], 'All bets cleared.')
      case 'reset':
        return EMPTY_BET_SLIP
    }
  }
}

export const betSlipReducer = createBetSlipReducer()

/** Why "Review bets" is not available, or `null` when it is. */
export type ReviewBlock = 'empty' | 'insufficient-balance' | 'unavailable' | null

/**
 * `balance` is the last server-confirmed balance, or `null` when none is known (loading, failed,
 * no wallet). An unknown balance never blocks: it is not zero, and the server checks the real balance
 * when the spin is sent.
 */
export function reviewBlock(bets: readonly SlipBet[], balance: number | null): ReviewBlock {
  if (bets.length === 0) return 'empty'
  if (balance !== null && balance < totalStake(bets)) return 'insufficient-balance'
  return null
}

/** The last server-confirmed balance, or `null` when none is known; unknown is never zero. */
export function knownBalance(wallet: WalletState): number | null {
  return wallet.status === 'loaded' ? wallet.balance : null
}

/** The visible reason why "Review bets" is not available (also its accessible description). */
export function blockReason(block: ReviewBlock, bets: readonly SlipBet[], balance: number | null): string | null {
  if (block === 'unavailable') return 'Roulette is not available on this server yet.'
  if (block === 'empty') return 'Place at least one bet to review it.'
  if (block === 'insufficient-balance' && balance !== null) {
    return `Your balance is too low for these bets: ${formatCoins(totalStake(bets))} in bets, ${formatCoins(balance)} available.`
  }
  return null
}

/** The bets of a frozen request as slip entries, in request order (to show an attempt after a reload). */
export function slipFromRequest(request: RouletteSpinRequest): SlipBet[] {
  return request.bets.map((bet) => {
    const entry = cellFor(bet)
    return { key: entry.key, selection: entry.selection, amount: bet.amount }
  })
}

function toBet(selection: RouletteSelection, amount: number): RouletteBet {
  // Built property by property: exactly the category, its one option property and the amount.
  switch (selection.category) {
    case 'STRAIGHT':
      return { category: 'STRAIGHT', number: selection.number, amount }
    case 'RED_BLACK':
      return { category: 'RED_BLACK', color: selection.color, amount }
    case 'EVEN_ODD':
      return { category: 'EVEN_ODD', parity: selection.parity, amount }
    case 'LOW_HIGH':
      return { category: 'LOW_HIGH', range: selection.range, amount }
    case 'DOZEN':
      return { category: 'DOZEN', dozen: selection.dozen, amount }
  }
}

/**
 * The body of `POST /api/v1/games/roulette/spins` for the slip: `{ bets: [{ category, <option>,
 * amount }] }` in slip order, frozen (W5-07 sends one body unchanged on every retry). Throws for a slip
 * the contract does not accept, so an invalid body is never built.
 */
export function toSpinRequest(bets: readonly SlipBet[], limits: SlipLimits = ROULETTE_LIMITS): RouletteSpinRequest {
  if (bets.length < 1 || bets.length > limits.maxBets) {
    throw new RangeError(`A spin needs 1 to ${limits.maxBets} bets, not ${bets.length}`)
  }
  const keys = new Set<string>()
  for (const bet of bets) {
    const key = cellFor(bet.selection).key
    if (keys.has(key)) throw new RangeError(`The selection ${key} appears twice`)
    keys.add(key)
    if (!isWholeAmount(bet.amount, limits)) throw new RangeError(`Not a valid amount for ${key}: ${bet.amount}`)
  }
  if (totalStake(bets) > limits.maxTotalStake) throw new RangeError(`The total stake is above ${limits.maxTotalStake}`)

  return Object.freeze({ bets: Object.freeze(bets.map((bet) => Object.freeze(toBet(bet.selection, bet.amount)))) })
}
