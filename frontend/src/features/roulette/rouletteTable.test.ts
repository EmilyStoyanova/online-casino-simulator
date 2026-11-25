import { describe, expect, it } from 'vitest'
import { ROULETTE_BET_CATEGORIES } from '../../types/roulette'
import {
  cellAccessibleName,
  cellFor,
  DOZEN_CELLS,
  NUMBER_CELLS,
  OUTSIDE_CELLS,
  selectionKey,
  TABLE_CELLS,
} from './rouletteTable'

/** Copied from the contract (`RouletteBetColor` in openapi.yaml), not from the production code. */
const CONTRACT_RED = [1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]
const CONTRACT_BLACK = [2, 4, 6, 8, 10, 11, 13, 15, 17, 20, 22, 24, 26, 28, 29, 31, 33, 35]

describe('AC-W5-06.1: exactly the cells of the five contract categories', () => {
  it('has 37 numbers, 3 dozens and 6 even-money cells: 46 cells, nothing else', () => {
    expect(NUMBER_CELLS).toHaveLength(37)
    expect(DOZEN_CELLS).toHaveLength(3)
    expect(OUTSIDE_CELLS).toHaveLength(6)
    expect(TABLE_CELLS).toHaveLength(46)
    expect(new Set(TABLE_CELLS.map((cell) => cell.key)).size).toBe(46)
  })

  it('offers every number 0–36 once, in ascending order, as straight bets', () => {
    expect(NUMBER_CELLS.map((cell) => cell.selection)).toEqual(
      Array.from({ length: 37 }, (_, number) => ({ category: 'STRAIGHT', number })),
    )
  })

  it('offers the three dozens and the six even-money selections of the contract', () => {
    expect(DOZEN_CELLS.map((cell) => cell.selection)).toEqual([
      { category: 'DOZEN', dozen: 'FIRST' },
      { category: 'DOZEN', dozen: 'SECOND' },
      { category: 'DOZEN', dozen: 'THIRD' },
    ])
    expect(OUTSIDE_CELLS.map((cell) => cell.selection)).toEqual([
      { category: 'LOW_HIGH', range: 'LOW' },
      { category: 'EVEN_ODD', parity: 'EVEN' },
      { category: 'RED_BLACK', color: 'RED' },
      { category: 'RED_BLACK', color: 'BLACK' },
      { category: 'EVEN_ODD', parity: 'ODD' },
      { category: 'LOW_HIGH', range: 'HIGH' },
    ])
  })

  it('uses only the five contract categories: no column, split, street or corner bet', () => {
    const categories = new Set(TABLE_CELLS.map((cell) => cell.selection.category))
    expect([...categories].sort()).toEqual([...ROULETTE_BET_CATEGORIES].sort())
    for (const cell of TABLE_CELLS) {
      expect(cell.name, cell.key).not.toMatch(/column|split|street|corner|line|basket/i)
    }
  })

  it('finds the cell of every selection and refuses selections the table does not offer', () => {
    for (const cell of TABLE_CELLS) expect(cellFor(cell.selection)).toBe(cell)
    expect(() => cellFor({ category: 'STRAIGHT', number: 37 })).toThrow(RangeError)
    expect(() => cellFor({ category: 'STRAIGHT', number: -1 })).toThrow(RangeError)
    expect(selectionKey({ category: 'DOZEN', dozen: 'SECOND' })).toBe('DOZEN:SECOND')
  })
})

describe('AC-W5-06.2: colours, odds and accessible names', () => {
  it('colours the numbers as the contract lists them: 18 red, 18 black, zero green', () => {
    const byColor = (color: string) => NUMBER_CELLS.filter((cell) => cell.color === color).map((cell) => Number(cell.label))
    expect(byColor('RED')).toEqual(CONTRACT_RED)
    expect(byColor('BLACK')).toEqual(CONTRACT_BLACK)
    expect(byColor('GREEN')).toEqual([0])
  })

  it('colours the red and black cells and leaves the other outside cells uncoloured', () => {
    expect(OUTSIDE_CELLS.map((cell) => [cell.label, cell.color])).toEqual([
      ['Low', null],
      ['Even', null],
      ['Red', 'RED'],
      ['Black', 'BLACK'],
      ['Odd', null],
      ['High', null],
    ])
    expect(DOZEN_CELLS.every((cell) => cell.color === null)).toBe(true)
  })

  it('states the contract odds of each category: 35 to 1, 2 to 1, 1 to 1', () => {
    for (const cell of NUMBER_CELLS) expect(cell.odds).toBe('35 to 1')
    for (const cell of DOZEN_CELLS) expect(cell.odds).toBe('2 to 1')
    for (const cell of OUTSIDE_CELLS) expect(cell.odds).toBe('1 to 1')
  })

  it('names number, colour, bet type and odds, and the placed amount', () => {
    const name = (index: number, placed?: number) => cellAccessibleName(TABLE_CELLS[index] as (typeof TABLE_CELLS)[number], placed)
    expect(name(0)).toBe('0, green. Straight bet, pays 35 to 1.')
    expect(name(17)).toBe('17, black. Straight bet, pays 35 to 1.')
    expect(name(32, 20)).toBe('32, red. Straight bet, pays 35 to 1. 20 coins placed.')
    expect(name(37)).toBe('1st dozen, 1 to 12. Dozen bet, pays 2 to 1.')
    expect(name(39, 1)).toBe('3rd dozen, 25 to 36. Dozen bet, pays 2 to 1. 1 coin placed.')
    expect(OUTSIDE_CELLS.map((cell) => cellAccessibleName(cell))).toEqual([
      'Low, 1 to 18. Low or high bet, pays 1 to 1.',
      'Even. Even or odd bet, pays 1 to 1.',
      'Red. Red or black bet, pays 1 to 1.',
      'Black. Red or black bet, pays 1 to 1.',
      'Odd. Even or odd bet, pays 1 to 1.',
      'High, 19 to 36. Low or high bet, pays 1 to 1.',
    ])
  })

  it('starts every accessible name with the visible label, for speech input', () => {
    for (const cell of TABLE_CELLS) {
      expect(cellAccessibleName(cell).startsWith(cell.label), cell.key).toBe(true)
    }
  })
})
