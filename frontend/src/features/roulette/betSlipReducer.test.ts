import { describe, expect, it } from 'vitest'
import type { RouletteSelection } from '../../types/roulette'
import {
  betSlipReducer,
  blockReason,
  CHIP_VALUES,
  createBetSlipReducer,
  DEFAULT_CHIP,
  EMPTY_BET_SLIP,
  knownBalance,
  reviewBlock,
  toSpinRequest,
  totalStake,
  type BetSlipAction,
  type BetSlipState,
  type SlipBet,
} from './betSlipReducer'
import { selectionKey } from './rouletteTable'

const RED: RouletteSelection = { category: 'RED_BLACK', color: 'RED' }
const n = (number: number): RouletteSelection => ({ category: 'STRAIGHT', number })
const add = (selection: RouletteSelection, amount: number): BetSlipAction => ({ type: 'add', selection, amount })

function run(actions: BetSlipAction[], reducer = betSlipReducer, from: BetSlipState = EMPTY_BET_SLIP): BetSlipState {
  return actions.reduce(reducer, from)
}

const summary = (state: BetSlipState) => state.bets.map((bet) => [bet.key, bet.amount])
const bet = (selection: RouletteSelection, amount: number): SlipBet => ({ key: selectionKey(selection), selection, amount })

describe('chips', () => {
  it('offers 1, 5, 10, 25, 100 and 500 coins, 10 by default', () => {
    expect(CHIP_VALUES).toEqual([1, 5, 10, 25, 100, 500])
    expect(DEFAULT_CHIP).toBe(10)
  })
})

describe('AC-W5-06.4: one bet per selection and the contract limits', () => {
  it('adds a chip as a new bet and announces the bet and the total', () => {
    const state = run([add(RED, 10)])

    expect(summary(state)).toEqual([['RED_BLACK:RED', 10]])
    expect(state.feedback).toEqual({ id: 1, tone: 'change', message: 'Red: 10 coins. 1 bet, total 10 coins.' })
  })

  it('adds further chips on the same selection to the one existing bet', () => {
    const state = run([add(RED, 10), add(n(17), 5), add(RED, 25), add(RED, 1)])

    expect(summary(state)).toEqual([
      ['RED_BLACK:RED', 36],
      ['STRAIGHT:17', 5],
    ])
    expect(state.feedback?.message).toBe('Red: 36 coins. 2 bets, total 41 coins.')
  })

  it('caps a bet at 1,000 coins and says so; a further chip is refused and announced', () => {
    const capped = run([add(n(7), 500), add(n(7), 100), add(n(7), 100), add(n(7), 100), add(n(7), 100), add(n(7), 500)])

    expect(summary(capped)).toEqual([['STRAIGHT:7', 1000]])
    expect(capped.feedback).toMatchObject({ tone: 'change', message: 'Number 7: 1,000 coins, the maximum per bet. 1 bet, total 1,000 coins.' })

    const refused = betSlipReducer(capped, add(n(7), 1))
    expect(refused.bets).toBe(capped.bets)
    expect(refused.feedback).toMatchObject({ tone: 'refused', message: 'Not added: Number 7 already has 1,000 coins, the maximum per bet.' })
  })

  it('refuses an eleventh selection and announces it; chips on the ten existing bets still add', () => {
    const ten = run(Array.from({ length: 10 }, (_, index) => add(n(index), 10)))
    expect(ten.bets).toHaveLength(10)

    const refused = betSlipReducer(ten, add(RED, 10))
    expect(refused.bets).toBe(ten.bets)
    expect(refused.feedback).toMatchObject({ tone: 'refused', message: 'Not added: up to 10 different bets per spin. Remove a bet to choose Red.' })

    expect(summary(betSlipReducer(refused, add(n(3), 5)))[3]).toEqual(['STRAIGHT:3', 15])
  })

  it('refuses an addition that would take the total above the limit, for a new and for an existing bet', () => {
    // 10 × 1,000 cannot exceed 10,000 today, so the rule is shown with a smaller total limit.
    const reducer = createBetSlipReducer({ maxBets: 10, minAmount: 1, maxAmount: 1000, maxTotalStake: 1200 })
    const state = run([add(n(1), 500), add(n(2), 500), add(n(3), 100)], reducer)
    expect(totalStake(state.bets)).toBe(1100)

    const newBet = reducer(state, add(RED, 500))
    expect(newBet.bets).toBe(state.bets)
    expect(newBet.feedback).toMatchObject({ tone: 'refused', message: 'Not added: up to 1,200 coins per spin.' })

    const existing = reducer(state, add(n(3), 500))
    expect(existing.bets).toBe(state.bets)
    expect(existing.feedback?.tone).toBe('refused')

    expect(totalStake(reducer(state, add(n(3), 100)).bets)).toBe(1200)
  })

  it('enforces the contract total of 10,000 coins: the full slip of 10 × 1,000 is accepted and is the maximum', () => {
    let state = EMPTY_BET_SLIP
    for (let number = 0; number < 10; number += 1) {
      state = run([add(n(number), 500), add(n(number), 500)], betSlipReducer, state)
    }
    expect(totalStake(state.bets)).toBe(10_000)
    expect(betSlipReducer(state, add(n(0), 1)).feedback?.tone).toBe('refused')
    expect(betSlipReducer(state, add(RED, 1)).feedback?.tone).toBe('refused')
  })

  it('gives every action a new feedback id, so a repeated refusal is announced again', () => {
    const ten = run(Array.from({ length: 10 }, (_, index) => add(n(index), 10)))
    const first = betSlipReducer(ten, add(RED, 10))
    const second = betSlipReducer(first, add(RED, 10))

    expect(second.feedback?.message).toBe(first.feedback?.message)
    expect(second.feedback?.id).toBe((first.feedback?.id ?? 0) + 1)
  })

  it('throws for a value that is not a chip amount or a selection the table does not offer', () => {
    expect(() => betSlipReducer(EMPTY_BET_SLIP, add(RED, 0))).toThrow(RangeError)
    expect(() => betSlipReducer(EMPTY_BET_SLIP, add(RED, 2.5))).toThrow(RangeError)
    expect(() => betSlipReducer(EMPTY_BET_SLIP, add(RED, 1001))).toThrow(RangeError)
    expect(() => betSlipReducer(EMPTY_BET_SLIP, add(n(37), 10))).toThrow(RangeError)
  })

  it('never changes the previous state (pure reducer)', () => {
    const before = run([add(RED, 10)])
    const snapshot = JSON.stringify(before)
    run([add(RED, 10), { type: 'remove', key: 'RED_BLACK:RED' }, { type: 'clear' }], betSlipReducer, before)
    expect(JSON.stringify(before)).toBe(snapshot)
  })
})

describe('AC-W5-06.5: remove, clear and the total stake', () => {
  it('removes one whole bet and keeps the order of the others', () => {
    const state = run([add(n(1), 10), add(RED, 25), add(n(2), 5), { type: 'remove', key: 'RED_BLACK:RED' }])

    expect(summary(state)).toEqual([
      ['STRAIGHT:1', 10],
      ['STRAIGHT:2', 5],
    ])
    expect(state.feedback?.message).toBe('Red removed. 2 bets, total 15 coins.')
    expect(run([{ type: 'remove', key: 'STRAIGHT:1' }, { type: 'remove', key: 'STRAIGHT:2' }], betSlipReducer, state).feedback?.message).toBe(
      'Number 2 removed. No bets left.',
    )
  })

  it('clears all bets; clearing or removing nothing changes nothing', () => {
    const state = run([add(n(1), 10), add(RED, 25), { type: 'clear' }])

    expect(state.bets).toEqual([])
    expect(state.feedback?.message).toBe('All bets cleared.')
    expect(betSlipReducer(state, { type: 'clear' })).toBe(state)
    expect(betSlipReducer(state, { type: 'remove', key: 'STRAIGHT:1' })).toBe(state)
  })

  it('computes the total stake as the sum of the slip', () => {
    expect(totalStake([])).toBe(0)
    expect(totalStake(run([add(n(1), 10), add(RED, 25), add(n(1), 500)]).bets)).toBe(535)
  })
})

describe('AC-W5-06.5: when "Review bets" is available', () => {
  const bets = [bet(RED, 600), bet(n(17), 500)]

  it('is blocked for an empty slip', () => {
    expect(reviewBlock([], 1000)).toBe('empty')
    expect(reviewBlock([], null)).toBe('empty')
    expect(blockReason('empty', [], null)).toBe('Place at least one bet to review it.')
  })

  it('is blocked when the known balance is lower than the total, and explains it', () => {
    expect(reviewBlock(bets, 1099)).toBe('insufficient-balance')
    expect(blockReason('insufficient-balance', bets, 1099)).toBe(
      'Your balance is too low for these bets: 1,100 coins in bets, 1,099 coins available.',
    )
    expect(reviewBlock(bets, 1100)).toBeNull()
    expect(reviewBlock(bets, 0)).toBe('insufficient-balance')
  })

  it('is not blocked by an unknown balance: unknown is not zero, the server decides', () => {
    expect(reviewBlock(bets, null)).toBeNull()
    expect(blockReason(null, bets, null)).toBeNull()
    expect(knownBalance({ status: 'loading' })).toBeNull()
    expect(knownBalance({ status: 'failed', message: 'x' })).toBeNull()
    expect(knownBalance({ status: 'inactive' })).toBeNull()
  })

  it('uses the last confirmed balance, also while it is refreshed or possibly out of date', () => {
    for (const refresh of ['idle', 'refreshing', 'failed'] as const) {
      expect(knownBalance({ status: 'loaded', balance: 0, updatedAt: null, refresh })).toBe(0)
    }
  })
})

describe('AC-W5-06.6: toSpinRequest builds the contract body', () => {
  it('serializes each of the five categories with exactly its one option property', () => {
    const request = toSpinRequest([
      bet(n(17), 10),
      bet({ category: 'RED_BLACK', color: 'BLACK' }, 25),
      bet({ category: 'EVEN_ODD', parity: 'ODD' }, 5),
      bet({ category: 'LOW_HIGH', range: 'HIGH' }, 1),
      bet({ category: 'DOZEN', dozen: 'THIRD' }, 1000),
    ])

    expect(JSON.parse(JSON.stringify(request))).toEqual({
      bets: [
        { category: 'STRAIGHT', number: 17, amount: 10 },
        { category: 'RED_BLACK', color: 'BLACK', amount: 25 },
        { category: 'EVEN_ODD', parity: 'ODD', amount: 5 },
        { category: 'LOW_HIGH', range: 'HIGH', amount: 1 },
        { category: 'DOZEN', dozen: 'THIRD', amount: 1000 },
      ],
    })
    expect(Object.keys(request)).toEqual(['bets'])
    for (const entry of request.bets) {
      expect(Object.keys(entry)).toHaveLength(3)
      expect(Number.isInteger(entry.amount)).toBe(true)
    }
  })

  it('serializes zero and the other options of every category', () => {
    const request = toSpinRequest([
      bet(n(0), 1),
      bet({ category: 'RED_BLACK', color: 'RED' }, 1),
      bet({ category: 'EVEN_ODD', parity: 'EVEN' }, 1),
      bet({ category: 'LOW_HIGH', range: 'LOW' }, 1),
      bet({ category: 'DOZEN', dozen: 'FIRST' }, 1),
      bet({ category: 'DOZEN', dozen: 'SECOND' }, 1),
    ])
    expect(request.bets.map((entry) => JSON.stringify(entry))).toEqual([
      '{"category":"STRAIGHT","number":0,"amount":1}',
      '{"category":"RED_BLACK","color":"RED","amount":1}',
      '{"category":"EVEN_ODD","parity":"EVEN","amount":1}',
      '{"category":"LOW_HIGH","range":"LOW","amount":1}',
      '{"category":"DOZEN","dozen":"FIRST","amount":1}',
      '{"category":"DOZEN","dozen":"SECOND","amount":1}',
    ])
  })

  it('keeps the slip order and sends nothing but the bets (no number, payout, balance or user)', () => {
    const state = run([add(n(5), 10), add(RED, 5), add(n(5), 10), add({ category: 'DOZEN', dozen: 'FIRST' }, 1)])
    const body = JSON.stringify(toSpinRequest(state.bets))

    expect(body).toBe(
      '{"bets":[{"category":"STRAIGHT","number":5,"amount":20},{"category":"RED_BLACK","color":"RED","amount":5},{"category":"DOZEN","dozen":"FIRST","amount":1}]}',
    )
    expect(body).not.toMatch(/winning|payout|balance|user|key|roundId/i)
  })

  it('ignores extra properties of a slip entry: each bet is rebuilt from the selection', () => {
    const polluted = { ...bet(n(9), 10), selection: { category: 'STRAIGHT', number: 9, color: 'RED', payout: 360 } } as SlipBet
    expect(JSON.stringify(toSpinRequest([polluted]))).toBe('{"bets":[{"category":"STRAIGHT","number":9,"amount":10}]}')
  })

  it('returns a frozen body, so a later retry sends exactly the same request', () => {
    const request = toSpinRequest([bet(RED, 10)])
    expect(Object.isFrozen(request)).toBe(true)
    expect(Object.isFrozen(request.bets)).toBe(true)
    expect(Object.isFrozen(request.bets[0])).toBe(true)
  })

  it('refuses a body the contract would reject', () => {
    expect(() => toSpinRequest([])).toThrow(RangeError)
    expect(() => toSpinRequest(Array.from({ length: 11 }, (_, index) => bet(n(index), 1)))).toThrow(RangeError)
    expect(() => toSpinRequest([bet(RED, 10), bet(RED, 5)])).toThrow(/twice/)
    expect(() => toSpinRequest([bet(RED, 0)])).toThrow(RangeError)
    expect(() => toSpinRequest([bet(RED, 1.5)])).toThrow(RangeError)
    expect(() => toSpinRequest([bet(RED, 1001)])).toThrow(RangeError)
    expect(() => toSpinRequest([bet(n(37), 1)])).toThrow(RangeError)
    expect(() => toSpinRequest([bet(RED, 600), bet(n(1), 600)], { maxBets: 10, minAmount: 1, maxAmount: 1000, maxTotalStake: 1000 })).toThrow(
      /total stake/,
    )
  })
})
