import type { RouletteSpinRequest, RouletteSpinResult } from '../types/roulette'
import { apiPost } from './apiClient'

const SPINS_PATH = '/api/v1/games/roulette/spins'

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

/** The canonical 36-character UUID form the backend accepts (either case). */
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * Plays one round of European roulette (`POST /api/v1/games/roulette/spins`, tag `Roulette`). The
 * server draws the number, computes the payouts and settles the wallet; the result is its stored round.
 *
 * The caller owns the idempotency: one key per new spin, and the same key with the same frozen
 * `request` for every retry of it. The same key and bets return the original round without charging
 * again; a new spin needs a new key. The body is sent exactly as given. Like every unsafe request it
 * carries the CSRF token, and the automatic retry after `403 CSRF_TOKEN_INVALID` repeats the same key
 * and body.
 *
 * There is deliberately no `AbortSignal`: the server may already be processing the round, so the
 * answer must not be dropped when a page is left.
 *
 * Rejects with a `TypeError` (nothing sent) if `idempotencyKey` is not a canonical UUID, and with an
 * `ApiError` for every failed request.
 */
export function spinRoulette(request: RouletteSpinRequest, idempotencyKey: string): Promise<RouletteSpinResult> {
  if (!CANONICAL_UUID.test(idempotencyKey)) {
    return Promise.reject(new TypeError('The Idempotency-Key must be a canonical UUID.'))
  }
  return apiPost<RouletteSpinResult>(SPINS_PATH, request, { headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } })
}
