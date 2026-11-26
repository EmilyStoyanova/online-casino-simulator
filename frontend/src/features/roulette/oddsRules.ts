import type { RouletteBetCategory } from '../../types/roulette'

/**
 * The rules of the five contract categories (`RouletteBetCategory` in `openapi.yaml`), as static text
 * for the "Odds and rules" panel (§5.8, Q8). They explain the game; they are never used to compute a
 * payout or a result (the server does both). `oddsRules.test.ts` checks every value against the contract.
 */

/** Pockets on a single-zero wheel: 0 to 36, each equally likely. */
export const POCKETS = 37

export interface CategoryRule {
  readonly category: RouletteBetCategory
  readonly name: string
  readonly covers: string
  /** How many of the 37 numbers win the bet. */
  readonly winningNumbers: number
  /** Net winnings per coin staked ("35 to 1"). */
  readonly netOdds: number
  /** Coins returned per coin staked on a win, stake included (the contract's "36 × amount"). */
  readonly returnedPerCoin: number
}

export const CATEGORY_RULES: readonly CategoryRule[] = Object.freeze([
  { category: 'STRAIGHT', name: 'Straight bet', covers: 'One number, 0 to 36', winningNumbers: 1, netOdds: 35, returnedPerCoin: 36 },
  { category: 'RED_BLACK', name: 'Red or black', covers: 'The 18 red or the 18 black numbers', winningNumbers: 18, netOdds: 1, returnedPerCoin: 2 },
  { category: 'EVEN_ODD', name: 'Even or odd', covers: '2, 4 … 36 or 1, 3 … 35', winningNumbers: 18, netOdds: 1, returnedPerCoin: 2 },
  { category: 'LOW_HIGH', name: 'Low or high', covers: '1–18 or 19–36', winningNumbers: 18, netOdds: 1, returnedPerCoin: 2 },
  { category: 'DOZEN', name: 'Dozen', covers: '1–12, 13–24 or 25–36', winningNumbers: 12, netOdds: 2, returnedPerCoin: 3 },
])

const percent = new Intl.NumberFormat('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** "18 in 37 (about 48.65 %)". */
export function chanceText(winningNumbers: number): string {
  return `${winningNumbers} in ${POCKETS} (about ${percent.format((winningNumbers / POCKETS) * 100)} %)`
}

/** The house edge of every category, 1/37, and the return to player, 36/37, as percentages. */
export const HOUSE_EDGE_PERCENT = percent.format((1 / POCKETS) * 100)
export const RETURN_TO_PLAYER_PERCENT = percent.format(((POCKETS - 1) / POCKETS) * 100)
