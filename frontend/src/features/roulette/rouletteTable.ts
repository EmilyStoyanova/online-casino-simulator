import type { RouletteNumberColor, RouletteSelection } from '../../types/roulette'
import { numberColor } from './rouletteNumbers'

/**
 * Static model of the European betting table (§5.3): the 37 numbers and the 9 outside cells of the
 * five contract categories, nothing else (no column, split, street or corner bet). Each cell carries
 * its contract selection, the texts shown on the cell and in the bet slip, and the odds of the
 * contract (`RouletteBetCategory`).
 *
 * The colours only decide how a cell looks. They never decide a result: the server draws the number
 * and computes every payout.
 */

export type BetCellGroup = 'numbers' | 'dozens' | 'outside'

export interface BetCell {
  /** The selection as one string, e.g. `STRAIGHT:17` or `RED_BLACK:RED`; one slip entry per key. */
  readonly key: string
  readonly selection: RouletteSelection
  readonly group: BetCellGroup
  /** The main text on the cell: "17", "1st dozen", "Low", "Red". */
  readonly label: string
  /** A second, smaller line on the cell: "1–12" for a dozen, "1–18" for low. */
  readonly detail: string | null
  /** The name in the bet slip and in announcements: "Number 17", "1st dozen (1–12)", "Red". */
  readonly name: string
  /** What the cell stands for, spoken first: "17, black", "1st dozen, 1 to 12", "Red". */
  readonly spoken: string
  /** Colour of the cell: the number's colour, red or black for a colour bet, `null` for the others. */
  readonly color: RouletteNumberColor | null
  /** "Straight bet", "Dozen bet", "Red or black bet", "Even or odd bet", "Low or high bet". */
  readonly betType: string
  /** Net winnings per coin staked, from the contract: "35 to 1", "2 to 1" or "1 to 1". */
  readonly odds: string
}

/** The odds of each category, as in the contract's `RouletteBetCategory` table. */
export const CATEGORY_ODDS = {
  STRAIGHT: '35 to 1',
  DOZEN: '2 to 1',
  RED_BLACK: '1 to 1',
  EVEN_ODD: '1 to 1',
  LOW_HIGH: '1 to 1',
} as const satisfies Record<RouletteSelection['category'], string>

const COLOR_WORD = { RED: 'red', BLACK: 'black', GREEN: 'green' } as const

/** The selection as one string; two bets with the same key are the same selection. */
export function selectionKey(selection: RouletteSelection): string {
  switch (selection.category) {
    case 'STRAIGHT':
      return `STRAIGHT:${selection.number}`
    case 'RED_BLACK':
      return `RED_BLACK:${selection.color}`
    case 'EVEN_ODD':
      return `EVEN_ODD:${selection.parity}`
    case 'LOW_HIGH':
      return `LOW_HIGH:${selection.range}`
    case 'DOZEN':
      return `DOZEN:${selection.dozen}`
  }
}

function cell(fields: Omit<BetCell, 'key' | 'odds'>): BetCell {
  return Object.freeze({ ...fields, key: selectionKey(fields.selection), odds: CATEGORY_ODDS[fields.selection.category] })
}

/** 0 to 36 in ascending order (the DOM order of the table in every layout). */
export const NUMBER_CELLS: readonly BetCell[] = Object.freeze(
  Array.from({ length: 37 }, (_, number) => {
    const color = numberColor(number)
    return cell({
      selection: { category: 'STRAIGHT', number },
      group: 'numbers',
      label: String(number),
      detail: null,
      name: `Number ${number}`,
      spoken: `${number}, ${COLOR_WORD[color]}`,
      color,
      betType: 'Straight bet',
    })
  }),
)

const DOZEN_FACTS = [
  ['FIRST', '1st', 1, 12],
  ['SECOND', '2nd', 13, 24],
  ['THIRD', '3rd', 25, 36],
] as const

/** The three dozens: 1–12, 13–24, 25–36. */
export const DOZEN_CELLS: readonly BetCell[] = Object.freeze(
  DOZEN_FACTS.map(([dozen, ordinal, from, to]) =>
    cell({
      selection: { category: 'DOZEN', dozen },
      group: 'dozens',
      label: `${ordinal} dozen`,
      detail: `${from}–${to}`,
      name: `${ordinal} dozen (${from}–${to})`,
      spoken: `${ordinal} dozen, ${from} to ${to}`,
      color: null,
      betType: 'Dozen bet',
    }),
  ),
)

/** The six even-money cells in the order of a real table: low, even, red, black, odd, high. */
export const OUTSIDE_CELLS: readonly BetCell[] = Object.freeze([
  cell({
    selection: { category: 'LOW_HIGH', range: 'LOW' },
    group: 'outside',
    label: 'Low',
    detail: '1–18',
    name: 'Low (1–18)',
    spoken: 'Low, 1 to 18',
    color: null,
    betType: 'Low or high bet',
  }),
  cell({
    selection: { category: 'EVEN_ODD', parity: 'EVEN' },
    group: 'outside',
    label: 'Even',
    detail: null,
    name: 'Even',
    spoken: 'Even',
    color: null,
    betType: 'Even or odd bet',
  }),
  cell({
    selection: { category: 'RED_BLACK', color: 'RED' },
    group: 'outside',
    label: 'Red',
    detail: null,
    name: 'Red',
    spoken: 'Red',
    color: 'RED',
    betType: 'Red or black bet',
  }),
  cell({
    selection: { category: 'RED_BLACK', color: 'BLACK' },
    group: 'outside',
    label: 'Black',
    detail: null,
    name: 'Black',
    spoken: 'Black',
    color: 'BLACK',
    betType: 'Red or black bet',
  }),
  cell({
    selection: { category: 'EVEN_ODD', parity: 'ODD' },
    group: 'outside',
    label: 'Odd',
    detail: null,
    name: 'Odd',
    spoken: 'Odd',
    color: null,
    betType: 'Even or odd bet',
  }),
  cell({
    selection: { category: 'LOW_HIGH', range: 'HIGH' },
    group: 'outside',
    label: 'High',
    detail: '19–36',
    name: 'High (19–36)',
    spoken: 'High, 19 to 36',
    color: null,
    betType: 'Low or high bet',
  }),
])

/** Every cell of the table in DOM order: 0–36, the dozens, the outside bets (46 cells). */
export const TABLE_CELLS: readonly BetCell[] = Object.freeze([...NUMBER_CELLS, ...DOZEN_CELLS, ...OUTSIDE_CELLS])

const CELLS_BY_KEY: ReadonlyMap<string, BetCell> = new Map(TABLE_CELLS.map((entry) => [entry.key, entry]))

/** The table cell of a selection; throws for a selection the table does not offer (e.g. number 37). */
export function cellFor(selection: RouletteSelection): BetCell {
  const found = CELLS_BY_KEY.get(selectionKey(selection))
  if (!found) throw new RangeError(`Not a selection of the table: ${selectionKey(selection)}`)
  return found
}

/** "17, black. Straight bet, pays 35 to 1." and, with a bet on it, "… 20 coins placed." */
export function cellAccessibleName(entry: BetCell, placed = 0): string {
  const base = `${entry.spoken}. ${entry.betType}, pays ${entry.odds}.`
  return placed > 0 ? `${base} ${formatCoins(placed)} placed.` : base
}

const coinFormat = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 0 })

/** "1 coin", "1,000 coins": the same wording as the `Coins` component, as plain text. */
export function formatCoins(amount: number): string {
  return `${coinFormat.format(amount)} ${Math.abs(amount) === 1 ? 'coin' : 'coins'}`
}

/** A whole amount with grouping only, for the chip marker on a cell: "1,000". */
export function formatAmount(amount: number): string {
  return coinFormat.format(amount)
}
