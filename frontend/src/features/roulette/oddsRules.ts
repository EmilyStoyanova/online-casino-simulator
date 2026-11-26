import { DEFAULT_LOCALE, type Locale } from '../../i18n/locales'
import { i18nFor, type MessageKey } from '../../i18n/translate'
import type { RouletteBetCategory } from '../../types/roulette'

/**
 * The rules of the five contract categories (`RouletteBetCategory` in `openapi.yaml`), for the "Odds
 * and rules" panel (§5.8, Q8). They explain the game; they are never used to compute a payout or a
 * result (the server does both). `oddsRules.test.ts` checks every value against the contract. The
 * names and descriptions are dictionary texts (`nameKey`, `coversKey`).
 */

/** Pockets on a single-zero wheel: 0 to 36, each equally likely. */
export const POCKETS = 37

export interface CategoryRule {
  readonly category: RouletteBetCategory
  readonly nameKey: MessageKey
  readonly coversKey: MessageKey
  /** How many of the 37 numbers win the bet. */
  readonly winningNumbers: number
  /** Net winnings per coin staked ("35 to 1"). */
  readonly netOdds: number
  /** Coins returned per coin staked on a win, stake included (the contract's "36 × amount"). */
  readonly returnedPerCoin: number
}

export const CATEGORY_RULES: readonly CategoryRule[] = Object.freeze([
  { category: 'STRAIGHT', nameKey: 'rule.STRAIGHT.name', coversKey: 'rule.STRAIGHT.covers', winningNumbers: 1, netOdds: 35, returnedPerCoin: 36 },
  { category: 'RED_BLACK', nameKey: 'rule.RED_BLACK.name', coversKey: 'rule.RED_BLACK.covers', winningNumbers: 18, netOdds: 1, returnedPerCoin: 2 },
  { category: 'EVEN_ODD', nameKey: 'rule.EVEN_ODD.name', coversKey: 'rule.EVEN_ODD.covers', winningNumbers: 18, netOdds: 1, returnedPerCoin: 2 },
  { category: 'LOW_HIGH', nameKey: 'rule.LOW_HIGH.name', coversKey: 'rule.LOW_HIGH.covers', winningNumbers: 18, netOdds: 1, returnedPerCoin: 2 },
  { category: 'DOZEN', nameKey: 'rule.DOZEN.name', coversKey: 'rule.DOZEN.covers', winningNumbers: 12, netOdds: 2, returnedPerCoin: 3 },
])

/** "18 in 37 (about 48.65 %)" / "18 от 37 (около 48,65 %)". */
export function chanceText(winningNumbers: number, locale: Locale = DEFAULT_LOCALE): string {
  const { t, percentFigure } = i18nFor(locale)
  return t('odds.chanceValue', { wins: winningNumbers, pockets: POCKETS, percent: percentFigure((winningNumbers / POCKETS) * 100) })
}

/** The house edge of every category, 1/37, as a percentage figure: "2.70" / "2,70". */
export function houseEdgePercent(locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).percentFigure((1 / POCKETS) * 100)
}

/** The return to player, 36/37, as a percentage figure: "97.30" / "97,30". */
export function returnToPlayerPercent(locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).percentFigure(((POCKETS - 1) / POCKETS) * 100)
}
