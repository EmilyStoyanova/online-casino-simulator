import { DEFAULT_LOCALE, type Locale } from '../../i18n/locales'
import { coinsMessage, i18nFor, msg, textMessage, type Message, type MessageKey } from '../../i18n/translate'
import type { RouletteDozen, RouletteNumberColor, RouletteSelection } from '../../types/roulette'
import { numberColor } from './rouletteNumbers'

/**
 * Static model of the European betting table (§5.3): the 37 numbers and the 9 outside cells of the
 * five contract categories, nothing else (no column, split, street or corner bet). Each cell carries
 * its contract selection, its colour and the facts its texts are built from; the texts themselves
 * ("Number 17", "1st dozen (1–12)", "Straight bet, pays 35 to 1") come from the dictionaries
 * (`cellText`, `cellNameMessage`), so every bet name follows the interface language.
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
  /** Colour of the cell: the number's colour, red or black for a colour bet, `null` for the others. */
  readonly color: RouletteNumberColor | null
  /** The dictionary key of the cell's word ("Low", "1st dozen", "Red"); `null` for a number. */
  readonly labelKey: MessageKey | null
  /** The numbers a dozen, low or high covers (shown as "1–12"); `null` for the others. */
  readonly range: readonly [from: number, to: number] | null
}

/** The texts of a cell in one language. */
export interface CellText {
  /** The main text on the cell: "17", "1st dozen", "Low", "Red". */
  readonly label: string
  /** A second, smaller line on the cell: "1–12" for a dozen, "1–18" for low. */
  readonly detail: string | null
  /** The name in the bet slip and in announcements: "Number 17", "1st dozen (1–12)", "Red". */
  readonly name: string
  /** What the cell stands for, spoken first: "17, black", "1st dozen, 1 to 12", "Red". */
  readonly spoken: string
  /** "Straight bet", "Dozen bet", "Red or black bet", "Even or odd bet", "Low or high bet". */
  readonly betType: string
  /** Net winnings per coin staked, from the contract: "35 to 1", "2 to 1" or "1 to 1". */
  readonly odds: string
}

/** The net odds of each category (winnings per coin staked), as in the contract's `RouletteBetCategory` table. */
export const CATEGORY_ODDS = {
  STRAIGHT: 35,
  DOZEN: 2,
  RED_BLACK: 1,
  EVEN_ODD: 1,
  LOW_HIGH: 1,
} as const satisfies Record<RouletteSelection['category'], number>

const BET_TYPE = {
  STRAIGHT: 'betType.STRAIGHT',
  DOZEN: 'betType.DOZEN',
  RED_BLACK: 'betType.RED_BLACK',
  EVEN_ODD: 'betType.EVEN_ODD',
  LOW_HIGH: 'betType.LOW_HIGH',
} as const satisfies Record<RouletteSelection['category'], MessageKey>

const COLOR_WORD = { RED: 'colorWord.RED', BLACK: 'colorWord.BLACK', GREEN: 'colorWord.GREEN' } as const satisfies Record<
  RouletteNumberColor,
  MessageKey
>

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

function cell(fields: Omit<BetCell, 'key'>): BetCell {
  return Object.freeze({ ...fields, key: selectionKey(fields.selection) })
}

/** 0 to 36 in ascending order (the DOM order of the table in every layout). */
export const NUMBER_CELLS: readonly BetCell[] = Object.freeze(
  Array.from({ length: 37 }, (_, number) =>
    cell({ selection: { category: 'STRAIGHT', number }, group: 'numbers', color: numberColor(number), labelKey: null, range: null }),
  ),
)

const DOZEN_FACTS: readonly (readonly [RouletteDozen, MessageKey, number, number])[] = [
  ['FIRST', 'cell.dozen.FIRST', 1, 12],
  ['SECOND', 'cell.dozen.SECOND', 13, 24],
  ['THIRD', 'cell.dozen.THIRD', 25, 36],
]

/** The three dozens: 1–12, 13–24, 25–36. */
export const DOZEN_CELLS: readonly BetCell[] = Object.freeze(
  DOZEN_FACTS.map(([dozen, labelKey, from, to]) =>
    cell({ selection: { category: 'DOZEN', dozen }, group: 'dozens', color: null, labelKey, range: [from, to] }),
  ),
)

/** The six even-money cells in the order of a real table: low, even, red, black, odd, high. */
export const OUTSIDE_CELLS: readonly BetCell[] = Object.freeze([
  cell({ selection: { category: 'LOW_HIGH', range: 'LOW' }, group: 'outside', color: null, labelKey: 'cell.LOW', range: [1, 18] }),
  cell({ selection: { category: 'EVEN_ODD', parity: 'EVEN' }, group: 'outside', color: null, labelKey: 'cell.EVEN', range: null }),
  cell({ selection: { category: 'RED_BLACK', color: 'RED' }, group: 'outside', color: 'RED', labelKey: 'color.RED', range: null }),
  cell({ selection: { category: 'RED_BLACK', color: 'BLACK' }, group: 'outside', color: 'BLACK', labelKey: 'color.BLACK', range: null }),
  cell({ selection: { category: 'EVEN_ODD', parity: 'ODD' }, group: 'outside', color: null, labelKey: 'cell.ODD', range: null }),
  cell({ selection: { category: 'LOW_HIGH', range: 'HIGH' }, group: 'outside', color: null, labelKey: 'cell.HIGH', range: [19, 36] }),
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

function labelMessage(entry: BetCell): Message {
  return entry.labelKey === null ? textMessage(String((entry.selection as { number: number }).number)) : msg(entry.labelKey)
}

function nameOf(entry: BetCell): Message {
  if (entry.selection.category === 'STRAIGHT') return msg('cell.numberName', { number: entry.selection.number })
  if (entry.range) return msg('cell.withRange', { label: labelMessage(entry), from: entry.range[0], to: entry.range[1] })
  return labelMessage(entry)
}

function spokenOf(entry: BetCell): Message {
  if (entry.selection.category === 'STRAIGHT') {
    return msg('cell.numberSpoken', { number: entry.selection.number, color: msg(COLOR_WORD[entry.color ?? 'GREEN']) })
  }
  if (entry.range) return msg('cell.rangeSpoken', { label: labelMessage(entry), from: entry.range[0], to: entry.range[1] })
  return labelMessage(entry)
}

/**
 * The name of a selection as a message ("Number 17", "Red"), for texts kept in state (the bet slip's
 * announcements), so they follow a change of language.
 */
export function cellNameMessage(selection: RouletteSelection): Message {
  return nameOf(cellFor(selection))
}

/** The texts of a cell in `locale` (English by default). */
export function cellText(entry: BetCell, locale: Locale = DEFAULT_LOCALE): CellText {
  const { t } = i18nFor(locale)
  const category = entry.selection.category
  return {
    label: t(labelMessage(entry)),
    detail: entry.range ? `${entry.range[0]}–${entry.range[1]}` : null,
    name: t(nameOf(entry)),
    spoken: t(spokenOf(entry)),
    betType: t(BET_TYPE[category]),
    odds: t('odds.toOne', { odds: CATEGORY_ODDS[category] }),
  }
}

/** "17, black. Straight bet, pays 35 to 1." and, with a bet on it, "… 20 coins placed." */
export function cellAccessibleName(entry: BetCell, placed = 0, locale: Locale = DEFAULT_LOCALE): string {
  const { t } = i18nFor(locale)
  const text = cellText(entry, locale)
  const base = t('cell.accessible', { spoken: text.spoken, betType: text.betType, odds: text.odds })
  return placed > 0 ? t('cell.placed', { base, amount: coinsMessage(placed) }) : base
}

/** "1 coin", "1,000 coins": the same wording as the `Coins` component, as plain text. */
export function formatCoins(amount: number, locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).coins(amount)
}

/** A whole amount with grouping only, for the chip marker on a cell: "1,000". */
export function formatAmount(amount: number, locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).number(amount)
}
