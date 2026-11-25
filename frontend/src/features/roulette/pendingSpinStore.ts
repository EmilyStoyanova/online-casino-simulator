import type { RouletteBet, RouletteSpinRequest } from '../../types/roulette'
import { toSpinRequest, type SlipBet } from './betSlipReducer'
import { selectionKey } from './rouletteTable'

/**
 * The record of a spin whose outcome is not confirmed yet (§8.4, Q6), in `sessionStorage` under
 * `casino.roulette.pendingSpin.v1.<userId>`: written **before** the request is sent and kept until the
 * outcome is resolved, so a lost answer can be retried with the same key and the same body after a
 * reload. It holds no session id, token, email or balance.
 *
 * Every access is wrapped in `try`/`catch`: storage can be disabled, full or throw on access.
 */
export interface PendingSpin {
  readonly version: 1
  /** The signed-in user who created it; only that user's page reads it. */
  readonly userId: string
  /** The canonical UUID created for this spin, sent unchanged on every retry. */
  readonly idempotencyKey: string
  /** The exact frozen body, sent unchanged on every retry. */
  readonly request: RouletteSpinRequest
  /** Client time, for display only. */
  readonly createdAt: string
  /** An earlier send of this record ended with an unknown outcome. */
  readonly ambiguous: boolean
  /** `sending` while a request is (or was, if the page died) in flight; `unresolved` after an unknown outcome. */
  readonly state: 'sending' | 'unresolved'
}

export type PendingSpinLookup =
  | { readonly kind: 'none' }
  | { readonly kind: 'found'; readonly record: PendingSpin }
  /** A record is there but cannot be trusted; it cannot be retried safely (see `removeInvalidPendingSpin`). */
  | { readonly kind: 'invalid' }

const PREFIX = 'casino.roulette.pendingSpin.v1.'
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function pendingSpinStorageKey(userId: string): string {
  return `${PREFIX}${userId}`
}

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    return null
  }
}

/** The body rebuilt from its bets through `toSpinRequest`; `null` unless it is exactly a valid body. */
function validRequest(value: unknown): RouletteSpinRequest | null {
  if (typeof value !== 'object' || value === null) return null
  const bets = (value as { bets?: unknown }).bets
  if (!Array.isArray(bets) || Object.keys(value).length !== 1) return null
  try {
    const slip: SlipBet[] = (bets as RouletteBet[]).map((bet) => ({ key: selectionKey(bet), selection: bet, amount: bet.amount }))
    const rebuilt = toSpinRequest(slip)
    // Exactly the contract shape: no property more or less than the rebuilt body.
    return JSON.stringify(rebuilt) === JSON.stringify(value) ? rebuilt : null
  } catch {
    return null
  }
}

/** A parsed record of `userId`, or `null` if anything about it is wrong. */
function validRecord(value: unknown, userId: string): PendingSpin | null {
  if (typeof value !== 'object' || value === null) return null
  const record = value as Record<string, unknown>
  const request = validRequest(record.request)
  if (
    record.version !== 1 ||
    record.userId !== userId ||
    typeof record.idempotencyKey !== 'string' ||
    !CANONICAL_UUID.test(record.idempotencyKey) ||
    request === null ||
    typeof record.createdAt !== 'string' ||
    typeof record.ambiguous !== 'boolean' ||
    (record.state !== 'sending' && record.state !== 'unresolved')
  ) {
    return null
  }
  return Object.freeze({
    version: 1,
    userId,
    idempotencyKey: record.idempotencyKey,
    request,
    createdAt: record.createdAt,
    ambiguous: record.ambiguous,
    state: record.state,
  })
}

/**
 * The pending spin of `userId`, if any. A record in state `sending` belongs to a page that died while
 * its request was in flight: it is returned as `unresolved` and `ambiguous` (§8.4). A record that cannot
 * be parsed, has another version, another user id inside or an invalid key or body is `invalid`.
 * Reading changes nothing (React may call it twice during render), and nothing is ever sent from here.
 */
export function loadPendingSpin(userId: string): PendingSpinLookup {
  const store = storage()
  if (!store) return { kind: 'none' }
  let raw: string | null
  try {
    raw = store.getItem(pendingSpinStorageKey(userId))
  } catch {
    return { kind: 'none' }
  }
  if (raw === null) return { kind: 'none' }

  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    parsed = null
  }
  const record = validRecord(parsed, userId)
  if (!record) return { kind: 'invalid' }
  return {
    kind: 'found',
    record: record.state === 'sending' ? Object.freeze({ ...record, ambiguous: true, state: 'unresolved' }) : record,
  }
}

/** Removes the record of `userId` if it is (still) invalid; a valid record is kept. */
export function removeInvalidPendingSpin(userId: string): void {
  const store = storage()
  if (store && loadPendingSpin(userId).kind === 'invalid') removeItem(store, userId)
}

/** Writes the record; `false` if storage is unavailable or the write failed (the caller must not send). */
export function savePendingSpin(record: PendingSpin): boolean {
  const store = storage()
  if (!store) return false
  try {
    const json = JSON.stringify(record)
    store.setItem(pendingSpinStorageKey(record.userId), json)
    // Read back: a silently ignored write would make the spin unrecoverable.
    return store.getItem(pendingSpinStorageKey(record.userId)) === json
  } catch {
    return false
  }
}

function removeItem(store: Storage, userId: string): void {
  try {
    store.removeItem(pendingSpinStorageKey(userId))
  } catch {
    // Nothing more can be done; a leftover record is shown again and can be retried safely.
  }
}

/**
 * Removes the record of `userId` once its outcome is resolved, but only if it is still the record of
 * `idempotencyKey`: a newer attempt (e.g. from another tab of the same session) is never removed.
 */
export function clearPendingSpin(userId: string, idempotencyKey: string): void {
  const store = storage()
  if (!store) return
  try {
    const raw = store.getItem(pendingSpinStorageKey(userId))
    if (raw === null) return
    const parsed = JSON.parse(raw) as { idempotencyKey?: unknown }
    if (parsed.idempotencyKey !== idempotencyKey) return
  } catch {
    // An unreadable record is not this attempt's; it is handled (removed) by the next load.
    return
  }
  removeItem(store, userId)
}
