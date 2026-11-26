import { describe, expect, it, vi } from 'vitest'
import { WALLET_TRANSACTION_TYPES } from '../../types/wallet'
import { formatEntryTime, formatSignedCoins, transactionTypeLabel } from './walletFormat'

describe('transactionTypeLabel', () => {
  it('labels every entry type of the contract', () => {
    expect(WALLET_TRANSACTION_TYPES.map((type) => transactionTypeLabel(type))).toEqual(['Starting balance', 'Roulette stake', 'Roulette payout'])
    expect(WALLET_TRANSACTION_TYPES.map((type) => transactionTypeLabel(type, 'bg'))).toEqual([
      'Начален баланс',
      'Залог в рулетка',
      'Изплащане от рулетка',
    ])
    expect(transactionTypeLabel('LEARNING_REWARD', 'bg')).toBe('Друг запис')
  })

  it('shows any other value as "Other entry", including names of object properties', () => {
    for (const type of ['LEARNING_REWARD', 'roulette_stake', '', 'toString', '__proto__', 'constructor']) {
      expect(transactionTypeLabel(type), type).toBe('Other entry')
    }
  })
})

describe('formatSignedCoins', () => {
  it('always shows the sign as text: plus for a credit, a minus sign for a debit', () => {
    expect(formatSignedCoins(360)).toBe('+360 coins')
    expect(formatSignedCoins(-30)).toBe('−30 coins')
    expect(formatSignedCoins(1)).toBe('+1 coin')
    expect(formatSignedCoins(-1)).toBe('−1 coin')
  })

  it('groups whole coins like the balance and never uses a currency', () => {
    expect(formatSignedCoins(1_000)).toBe('+1,000 coins')
    expect(formatSignedCoins(-9_007_199_254_740_991)).toBe('−9,007,199,254,740,991 coins')
    expect(formatSignedCoins(12_345)).not.toMatch(/[$€£¥]|BGN|EUR|USD/)
  })
})

describe('formatEntryTime', () => {
  it('formats in the given time zone (en-GB, medium date, short time)', () => {
    const at = '2026-11-20T10:15:30.118204Z'
    expect(formatEntryTime(at, 'UTC')).toBe('20 Nov 2026, 10:15')
    expect(formatEntryTime(at, 'Europe/Sofia')).toBe('20 Nov 2026, 12:15')
    expect(formatEntryTime(at, 'America/New_York')).toBe('20 Nov 2026, 05:15')
  })

  it('uses the browser’s local time zone by default', () => {
    vi.stubEnv('TZ', 'Asia/Tokyo')

    // 23:30 UTC is already the next day in Tokyo.
    expect(formatEntryTime('2026-11-20T23:30:00Z')).toBe('21 Nov 2026, 08:30')
  })

  it('shows "—" for a value that is not a date instead of a wrong one', () => {
    expect(formatEntryTime('not a date', 'UTC')).toBe('—')
  })
})

describe('Bulgarian formatting', () => {
  it('groups coins with a (no-break) space and uses the Bulgarian plural, never a currency', () => {
    expect(formatSignedCoins(360, 'bg')).toBe('+360 монети')
    expect(formatSignedCoins(-1, 'bg')).toBe('−1 монета')
    // bg-BG groups from five digits, with U+00A0.
    expect(formatSignedCoins(1_000, 'bg')).toBe('+1000 монети')
    expect(formatSignedCoins(-12_345, 'bg')).toBe('−12\u00a0345 монети')
  })

  it('formats entry times with the Bulgarian date format', () => {
    expect(formatEntryTime('2026-11-20T10:15:30Z', 'Europe/Sofia', 'bg')).toMatch(/^20\.11\.2026 г\., 12:15/)
    expect(formatEntryTime('not a date', 'UTC', 'bg')).toBe('—')
  })
})
