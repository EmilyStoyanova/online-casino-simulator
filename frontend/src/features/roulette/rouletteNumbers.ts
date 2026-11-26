import type { RouletteNumberColor } from '../../types/roulette'

/**
 * Display facts of the single-zero (European) wheel. They only decide how numbers look (colours,
 * pocket order); every result and payout comes from the server, never from these values.
 */

/** The 37 pockets clockwise from zero, as on a European wheel. */
export const EUROPEAN_WHEEL_ORDER: readonly number[] = Object.freeze([
  0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18,
  29, 7, 28, 12, 35, 3, 26,
])

/** The red numbers, as listed in the contract (`RouletteBetColor`); 0 is green, the others black. */
export const RED_NUMBERS: ReadonlySet<number> = new Set([
  1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36,
])

/** The display colour of a number 0–36. */
export function numberColor(value: number): RouletteNumberColor {
  if (!Number.isInteger(value) || value < 0 || value > 36) {
    throw new RangeError(`Not a roulette number: ${value}`)
  }
  if (value === 0) return 'GREEN'
  return RED_NUMBERS.has(value) ? 'RED' : 'BLACK'
}
