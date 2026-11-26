import { msg, type Message } from '../../i18n/translate'
import { ApiError } from '../../services/apiClient'

/**
 * Classification of a failed spin request (§7.2). The question is whether the server **may** have
 * played the round:
 *
 * - `definitive`: the request was rejected and nothing was played by it;
 * - `uncertain`: the round may have been committed before the answer was lost (network error, `5xx`,
 *   a gateway without Problem Details, a `2xx` that was not the API, the busy wallet's `503`, anything
 *   unknown). Only a retry with the same key and body can tell, and it never charges twice.
 *
 * Decisions use the HTTP status and the Problem Details `code` only, never `title` or `detail`.
 */

export type SpinFailureClass = 'definitive' | 'uncertain'

/**
 * How the pending record of the request (§8.4) is affected:
 * - `resolves`: the answer proves that no round with this key exists (`400`, the `409`s): clear it;
 * - `before-lookup`: rejected before the server looked up the key (`401`, `403`, `404`, `405`, `413`,
 *   `415`): only *this* request did nothing, so an earlier ambiguous send may still have played;
 * - `unknown`: the outcome is unknown: keep the record and mark it ambiguous.
 */
export type RecordEffect = 'resolves' | 'before-lookup' | 'unknown'

/** A validation message of the server for one bet row (`bets[2].amount` → row 2), or for the whole slip. */
export interface BetFieldError {
  /** Index of the bet in the request, or `null` for a message about the request as a whole. */
  readonly betIndex: number | null
  readonly field: string
  /** The server's text as it was sent (translated for display by `serverFieldMessage`). */
  readonly message: string
}

export interface SpinFailure {
  readonly kind: SpinFailureClass
  readonly recordEffect: RecordEffect
  /** The Problem Details `code`, `NETWORK` for no answer from the API, or `UNKNOWN`. */
  readonly code: string
  readonly status: number
  /** What the player reads; translated when shown. */
  readonly message: Message
  readonly fieldErrors: readonly BetFieldError[]
  /** Re-read the wallet: the server's balance explains the rejection or may have changed. */
  readonly refreshBalance: boolean
  /** `404`: this server has no roulette endpoint; the page shows its unavailable state (§7.7). */
  readonly unavailable: boolean
}

export const UNCERTAIN_MESSAGE: Message = msg('spin.error.uncertain')
export const BUSY_MESSAGE: Message = msg('spin.error.busy')
export const UNAVAILABLE_MESSAGE: Message = msg('roulette.unavailable')

const STATUSES_BEFORE_LOOKUP: ReadonlySet<number> = new Set([401, 403, 404, 405, 413, 415])

const BET_FIELD = /^bets\[(\d+)\]/

function fieldErrors(error: ApiError): BetFieldError[] {
  return (error.problem?.errors ?? []).map(({ field, message }) => {
    const match = BET_FIELD.exec(field)
    return { betIndex: match ? Number(match[1]) : null, field, message }
  })
}

function definitive(
  error: ApiError,
  message: Message,
  recordEffect: RecordEffect,
  extra: Partial<Pick<SpinFailure, 'fieldErrors' | 'refreshBalance' | 'unavailable'>> = {},
): SpinFailure {
  return {
    kind: 'definitive',
    recordEffect,
    code: error.code ?? `HTTP_${error.status}`,
    status: error.status,
    message,
    fieldErrors: extra.fieldErrors ?? [],
    refreshBalance: extra.refreshBalance ?? false,
    unavailable: extra.unavailable ?? false,
  }
}

function uncertain(code: string, status: number, message: Message = UNCERTAIN_MESSAGE): SpinFailure {
  return { kind: 'uncertain', recordEffect: 'unknown', code, status, message, fieldErrors: [], refreshBalance: false, unavailable: false }
}

/** Classifies whatever `spinRoulette` rejected with. Anything unexpected is `uncertain` (safe). */
export function classifySpinError(error: unknown): SpinFailure {
  if (!(error instanceof ApiError)) return uncertain('UNKNOWN', 0)
  if (error.isUnavailable) return uncertain('NETWORK', error.status)
  const code = error.code

  switch (code) {
    case 'VALIDATION_FAILED':
      if (error.status === 400) {
        return definitive(error, msg('spin.error.validation'), 'resolves', {
          fieldErrors: fieldErrors(error),
        })
      }
      break
    case 'MALFORMED_REQUEST':
      if (error.status === 400) {
        return definitive(error, msg('error.malformed'), 'resolves')
      }
      break
    case 'INSUFFICIENT_FUNDS':
      if (error.status === 409) {
        return definitive(error, msg('spin.error.insufficientFunds'), 'resolves', { refreshBalance: true })
      }
      break
    case 'BALANCE_LIMIT_EXCEEDED':
      if (error.status === 409) {
        return definitive(error, msg('spin.error.balanceLimit'), 'resolves', {
          refreshBalance: true,
        })
      }
      break
    case 'IDEMPOTENCY_KEY_REUSED':
      if (error.status === 409) {
        // Cannot happen with a frozen body (§8.4); a defect. The round of this key exists: see the history.
        return definitive(error, msg('spin.error.keyReused'), 'resolves', { refreshBalance: true })
      }
      break
    case 'SERVICE_UNAVAILABLE':
      // The busy wallet: nothing was written, but the contract says to retry with the same key.
      if (error.status === 503) return uncertain(code, 503, BUSY_MESSAGE)
      break
    case 'SESSION_EXPIRED':
    case 'AUTHENTICATION_REQUIRED':
      return definitive(error, msg('session.ended'), 'before-lookup')
    case 'ACCESS_DENIED':
      return definitive(error, msg('spin.error.accessDenied'), 'before-lookup')
    case 'CSRF_TOKEN_INVALID':
      return definitive(error, msg('error.csrf'), 'before-lookup')
    default:
      break
  }

  if (error.status === 404) {
    return definitive(error, UNAVAILABLE_MESSAGE, 'before-lookup', { unavailable: true })
  }
  if (STATUSES_BEFORE_LOOKUP.has(error.status)) {
    return definitive(error, msg('spin.error.notAccepted'), 'before-lookup')
  }
  // 500, other 5xx with Problem Details, unknown 4xx and codes: the outcome is unknown.
  return uncertain(code ?? `HTTP_${error.status}`, error.status)
}
