import { describe, expect, it } from 'vitest'
import rawContract from '../../../../backend/src/main/resources/static/openapi.yaml?raw'
import { ROULETTE_BET_CATEGORIES } from '../../types/roulette'
import { CATEGORY_RULES, chanceText, houseEdgePercent, POCKETS, returnToPlayerPercent } from './oddsRules'
import { CATEGORY_ODDS } from './rouletteTable'

const contract = rawContract.replace(/\r\n/g, '\n')

/** The rows of the table in the description of `RouletteBetCategory`: category → its cells. */
function contractRows(): Map<string, string[]> {
  const start = contract.indexOf('\n    RouletteBetCategory:\n')
  const block = contract.slice(start, contract.indexOf('\n    RouletteBetColor:\n'))
  const rows = new Map<string, string[]>()
  for (const line of block.split('\n')) {
    const match = /^\s*\| `([A-Z_]+)` \|(.*)\|\s*$/.exec(line)
    if (match?.[1] && match[2]) rows.set(match[1], match[2].split('|').map((cell) => cell.trim()))
  }
  return rows
}

describe('AC-W5-08.5: the odds and rules match the contract (source of truth)', () => {
  it('covers exactly the five contract categories, in the contract order', () => {
    expect(CATEGORY_RULES.map((rule) => rule.category)).toEqual([...ROULETTE_BET_CATEGORIES])
    expect([...contractRows().keys()]).toEqual([...ROULETTE_BET_CATEGORIES])
  })

  it('states each category’s chance, odds and return as in the contract table', () => {
    const rows = contractRows()
    for (const rule of CATEGORY_RULES) {
      const [, probability, odds, payout] = rows.get(rule.category) ?? []
      expect(probability, rule.category).toMatch(new RegExp(`^${rule.winningNumbers}/37 ≈ `))
      // The contract gives four decimals; the panel shows two, rounded from the same fraction.
      const contractPercent = Number(/≈ ([\d.]+) %/.exec(probability ?? '')?.[1])
      expect(chanceText(rule.winningNumbers)).toBe(`${rule.winningNumbers} in 37 (about ${contractPercent.toFixed(2)} %)`)
      expect(odds, rule.category).toBe(`${rule.netOdds}:1`)
      expect(payout, rule.category).toBe(`${rule.returnedPerCoin} × amount`)
      // The same odds as the table cells' names.
      expect(CATEGORY_ODDS[rule.category]).toBe(rule.netOdds)
    }
  })

  it('has the same house edge of 1/37 for every category, as the contract says', () => {
    for (const rule of CATEGORY_RULES) {
      // Expected net result per coin: odds × P(win) − 1 × P(loss) = −1/37.
      const expected = (rule.netOdds * rule.winningNumbers - (POCKETS - rule.winningNumbers)) / POCKETS
      expect(expected, rule.category).toBeCloseTo(-1 / 37, 12)
    }
    expect(contract).toContain('house edge 1/37 ≈ 2.7027 %, return to player 36/37 ≈ 97.2973 %')
    expect(houseEdgePercent()).toBe((2.7027).toFixed(2))
    expect(returnToPlayerPercent()).toBe((97.2973).toFixed(2))
    // The same figures in Bulgarian, with a decimal comma.
    expect(houseEdgePercent('bg')).toBe('2,70')
    expect(returnToPlayerPercent('bg')).toBe('97,30')
    expect(chanceText(18, 'bg')).toBe('18 от 37 (около 48,65 %)')
  })

  it('says what the contract says about zero', () => {
    expect(contract).toContain('Zero wins only a `STRAIGHT` bet on `0`; it loses')
  })
})
