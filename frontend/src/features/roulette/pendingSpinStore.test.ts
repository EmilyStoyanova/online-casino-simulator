import { afterEach, describe, expect, it, vi } from 'vitest'
import { SPIN_KEY, spinRequest } from '../../test/fixtures'
import {
  clearPendingSpin,
  loadPendingSpin,
  pendingSpinStorageKey,
  removeInvalidPendingSpin,
  savePendingSpin,
  type PendingSpin,
} from './pendingSpinStore'

const ALICE = '0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10'
const BOB = '7c1d2e3f-4a5b-4c6d-9e8f-0a1b2c3d4e5f'

const record = (overrides: Partial<PendingSpin> = {}): PendingSpin => ({
  version: 1,
  userId: ALICE,
  idempotencyKey: SPIN_KEY,
  request: spinRequest,
  createdAt: '2026-11-25T10:00:00.000Z',
  ambiguous: false,
  state: 'sending',
  ...overrides,
})

const stored = (userId = ALICE) => sessionStorage.getItem(pendingSpinStorageKey(userId))

afterEach(() => {
  vi.restoreAllMocks()
})

describe('pendingSpinStore: the §8.4 record', () => {
  it('is stored per user under casino.roulette.pendingSpin.v1.<userId> with exactly the record fields', () => {
    expect(savePendingSpin(record())).toBe(true)

    expect(pendingSpinStorageKey(ALICE)).toBe(`casino.roulette.pendingSpin.v1.${ALICE}`)
    const raw = JSON.parse(stored() ?? 'null') as Record<string, unknown>
    expect(Object.keys(raw).sort()).toEqual(['ambiguous', 'createdAt', 'idempotencyKey', 'request', 'state', 'userId', 'version'])
    expect(raw.request).toEqual(spinRequest)
    // No session, token, credential or balance in browser storage.
    expect(JSON.stringify(raw)).not.toMatch(/XSRF|csrf|cookie|session|password|email|balance/i)
    expect(localStorage.length).toBe(0)
  })

  it('reads an unresolved record back unchanged', () => {
    savePendingSpin(record({ state: 'unresolved', ambiguous: true }))
    expect(loadPendingSpin(ALICE)).toEqual({ kind: 'found', record: record({ state: 'unresolved', ambiguous: true }) })
  })

  it('reads a record left in "sending" (the page died during the request) as unresolved and ambiguous', () => {
    savePendingSpin(record())
    expect(loadPendingSpin(ALICE)).toEqual({ kind: 'found', record: record({ state: 'unresolved', ambiguous: true }) })
  })

  it('never reads another user’s record', () => {
    savePendingSpin(record())
    expect(loadPendingSpin(BOB)).toEqual({ kind: 'none' })
  })

  it.each([
    ['not JSON', '{oops'],
    ['another version', JSON.stringify({ ...record(), version: 2 })],
    ['another user inside', JSON.stringify({ ...record(), userId: BOB })],
    ['a key that is not a UUID', JSON.stringify({ ...record(), idempotencyKey: 'abc' })],
    ['a body with an extra property', JSON.stringify({ ...record(), request: { ...spinRequest, userId: ALICE } })],
    ['a bet with two option properties', JSON.stringify({ ...record(), request: { bets: [{ category: 'STRAIGHT', number: 17, color: 'RED', amount: 10 }] } })],
    ['a bet above 1,000 coins', JSON.stringify({ ...record(), request: { bets: [{ category: 'STRAIGHT', number: 17, amount: 1001 }] } })],
    ['no bets', JSON.stringify({ ...record(), request: { bets: [] } })],
    ['an unknown state', JSON.stringify({ ...record(), state: 'done' })],
    ['an array', '[]'],
  ])('treats a record with %s as invalid: nothing to retry; reading changes nothing, removal is explicit', (_, raw) => {
    sessionStorage.setItem(pendingSpinStorageKey(ALICE), raw)

    expect(loadPendingSpin(ALICE)).toEqual({ kind: 'invalid' })
    expect(stored()).toBe(raw)
    removeInvalidPendingSpin(ALICE)
    expect(stored()).toBeNull()
  })

  it('removeInvalidPendingSpin keeps a valid record', () => {
    savePendingSpin(record())
    removeInvalidPendingSpin(ALICE)
    expect(stored()).not.toBeNull()
  })

  it('clears only the record of the given key', () => {
    savePendingSpin(record())
    clearPendingSpin(ALICE, '11111111-2222-4333-8444-555555555555')
    expect(stored()).not.toBeNull()
    clearPendingSpin(ALICE, SPIN_KEY)
    expect(stored()).toBeNull()
  })

  it('reports a failed write (full or blocked storage), so the caller does not send', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Quota exceeded', 'QuotaExceededError')
    })
    expect(savePendingSpin(record())).toBe(false)
  })

  it('reports a write that was silently dropped', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => undefined)
    expect(savePendingSpin(record())).toBe(false)
  })

  it('survives storage that throws on every access', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Denied', 'SecurityError')
    })
    expect(loadPendingSpin(ALICE)).toEqual({ kind: 'none' })
    expect(() => clearPendingSpin(ALICE, SPIN_KEY)).not.toThrow()
    expect(savePendingSpin(record())).toBe(false)
  })
})
