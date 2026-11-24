import { describe, expect, it } from 'vitest'
import rawContract from '../../../backend/src/main/resources/static/openapi.yaml?raw'
import { RED_NUMBERS } from '../features/roulette/rouletteNumbers'
import { IDEMPOTENCY_KEY_HEADER } from '../services/rouletteApi'
import { historyAfterSpin, spinResult, startingWallet } from '../test/fixtures'
import {
  ROULETTE_BET_CATEGORIES,
  ROULETTE_BET_COLORS,
  ROULETTE_DOZENS,
  ROULETTE_LIMITS,
  ROULETTE_NUMBER_COLORS,
  ROULETTE_PARITIES,
  ROULETTE_RANGES,
} from './roulette'
import { WALLET_HISTORY_LIMITS, WALLET_TRANSACTION_TYPES } from './wallet'

/**
 * Drift check between the frontend's mirrored contract values and `openapi.yaml` (the source of truth,
 * ADR-0004), read as plain text like `vercelConfig.test.ts` reads `vercel.json`: no YAML dependency.
 * It relies on the contract's formatting (2-space indentation, flow-style `enum` and `required`
 * lists); a formatting change makes a lookup fail loudly rather than pass silently.
 */

// A Windows checkout may have CRLF line endings.
const contract = rawContract.replace(/\r\n/g, '\n')

/** The block of `components.schemas.<name>` (4-space indentation), up to the next schema. */
function schema(name: string): string {
  const start = contract.indexOf(`\n    ${name}:\n`)
  if (start < 0) throw new Error(`Schema ${name} not found in openapi.yaml`)
  const block = contract.slice(start + 1)
  const end = block.slice(1).search(/\n {4}[A-Za-z]/)
  return end < 0 ? block : block.slice(0, end + 1)
}

/** A property block of a schema (8-space indentation), up to the next property. */
function property(schemaName: string, name: string): string {
  const block = schema(schemaName)
  const start = block.indexOf(`\n        ${name}:\n`)
  if (start < 0) throw new Error(`Property ${schemaName}.${name} not found in openapi.yaml`)
  const rest = block.slice(start + 1)
  const end = rest.slice(1).search(/\n {8}[A-Za-z]/)
  return end < 0 ? rest : rest.slice(0, end + 1)
}

function list(block: string, keyword: 'enum' | 'required'): string[] {
  const match = new RegExp(`\\n\\s+${keyword}: \\[([^\\]]*)\\]`).exec(block)
  if (!match?.[1]) throw new Error(`No flow-style ${keyword} list in:\n${block}`)
  return match[1].split(',').map((value) => value.trim())
}

function integer(block: string, keyword: string): number {
  const match = new RegExp(`\\n\\s+${keyword}: (\\d+)(?:\\n|$)`).exec(block)
  if (!match?.[1]) throw new Error(`No ${keyword} in:\n${block}`)
  return Number(match[1])
}

/** The block of one parameter of an operation path, from `- name: <name>` to the next item. */
function parameter(path: string, name: string): string {
  const operation = contract.slice(contract.indexOf(`\n  ${path}:\n`))
  const start = operation.indexOf(`- name: ${name}\n`)
  if (start < 0) throw new Error(`Parameter ${name} of ${path} not found in openapi.yaml`)
  const rest = operation.slice(start)
  const end = rest.slice(1).search(/\n\s+- name: |\n {6}requestBody:|\n {6}responses:/)
  return end < 0 ? rest : rest.slice(0, end + 1)
}

describe('contract drift: enums', () => {
  it.each([
    ['RouletteBetCategory', ROULETTE_BET_CATEGORIES],
    ['RouletteBetColor', ROULETTE_BET_COLORS],
    ['RouletteParity', ROULETTE_PARITIES],
    ['RouletteRange', ROULETTE_RANGES],
    ['RouletteDozen', ROULETTE_DOZENS],
    ['RouletteNumberColor', ROULETTE_NUMBER_COLORS],
    ['WalletTransactionType', WALLET_TRANSACTION_TYPES],
  ])('%s has exactly the values of the frontend', (name, values) => {
    expect(list(schema(name), 'enum')).toEqual([...values])
  })
})

describe('contract drift: number colours', () => {
  it('the red numbers of the wheel and the table are the contract’s list', () => {
    const description = schema('RouletteBetColor').replace(/\s+/g, ' ')
    const red = /Red: ([\d, ]+)\./.exec(description)?.[1]
    const black = /Black: ([\d, ]+)\./.exec(description)?.[1]
    const parse = (text: string | undefined) => (text ?? '').split(',').map((value) => Number(value.trim()))

    expect(parse(red)).toEqual([...RED_NUMBERS].sort((a, b) => a - b))
    expect(parse(black)).toHaveLength(18)
    expect(parse(black).some((value) => RED_NUMBERS.has(value))).toBe(false)
  })
})

describe('contract drift: limits', () => {
  it('bets per spin, amount per bet, straight numbers and total stake', () => {
    expect(integer(property('RouletteSpinRequest', 'bets'), 'minItems')).toBe(ROULETTE_LIMITS.minBets)
    expect(integer(property('RouletteSpinRequest', 'bets'), 'maxItems')).toBe(ROULETTE_LIMITS.maxBets)
    expect(integer(property('RouletteBet', 'amount'), 'minimum')).toBe(ROULETTE_LIMITS.minAmount)
    expect(integer(property('RouletteBet', 'amount'), 'maximum')).toBe(ROULETTE_LIMITS.maxAmount)
    expect(integer(property('RouletteBet', 'number'), 'minimum')).toBe(ROULETTE_LIMITS.minNumber)
    expect(integer(property('RouletteBet', 'number'), 'maximum')).toBe(ROULETTE_LIMITS.maxNumber)
    expect(integer(property('RouletteSpinResult', 'totalStake'), 'maximum')).toBe(ROULETTE_LIMITS.maxTotalStake)
  })

  it('page size of the wallet history', () => {
    const limit = parameter('/api/v1/wallet/transactions', 'limit')

    expect(integer(limit, 'minimum')).toBe(WALLET_HISTORY_LIMITS.minLimit)
    expect(integer(limit, 'maximum')).toBe(WALLET_HISTORY_LIMITS.maxLimit)
    expect(integer(limit, 'default')).toBe(WALLET_HISTORY_LIMITS.defaultLimit)
  })
})

describe('contract drift: shapes and headers', () => {
  it('the fixtures used by the tests have exactly the required properties of the response schemas', () => {
    expect(list(schema('Wallet'), 'required')).toEqual(Object.keys(startingWallet))
    expect(list(schema('WalletTransactionPage'), 'required')).toEqual(Object.keys(historyAfterSpin))
    expect(list(schema('WalletTransaction'), 'required')).toEqual(Object.keys(historyAfterSpin.items[0] ?? {}))
    expect(list(schema('RouletteSpinResult'), 'required')).toEqual(Object.keys(spinResult))
    expect(list(schema('RouletteSpinRequest'), 'required')).toEqual(['bets'])
    expect(list(schema('RouletteBet'), 'required')).toEqual(['category', 'amount'])
    expect(list(schema('RouletteSettledBet'), 'required')).toEqual(['category', 'amount', 'payout'])
  })

  it('the spin needs the Idempotency-Key header the client sends', () => {
    const header = parameter('/api/v1/games/roulette/spins', IDEMPOTENCY_KEY_HEADER)

    expect(header).toContain('in: header')
    expect(header).toContain('required: true')
    expect(header).toContain('format: uuid')
  })
})
