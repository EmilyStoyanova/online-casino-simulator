import { describe, expect, it } from 'vitest'
import { ApiError } from '../../services/apiClient'
import { BUSY_MESSAGE, classifySpinError, UNAVAILABLE_MESSAGE, UNCERTAIN_MESSAGE } from './spinErrors'

const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  new ApiError(`Request failed with status ${status}`, status, { title: 'Ignored title', detail: 'Ignored detail', status, code, ...extra })

describe('§7.2: definitive failures (nothing was played by this request)', () => {
  it('400 VALIDATION_FAILED resolves the record and maps the field errors to bet rows', () => {
    const failure = classifySpinError(
      problem(400, 'VALIDATION_FAILED', {
        errors: [
          { field: 'bets[1].amount', message: 'must be less than or equal to 1000' },
          { field: 'bets', message: 'contains the same selection twice' },
        ],
      }),
    )
    expect(failure).toMatchObject({ kind: 'definitive', recordEffect: 'resolves', code: 'VALIDATION_FAILED', refreshBalance: false })
    expect(failure.message).toBe('Some bets were not accepted. Change them and review again.')
    expect(failure.fieldErrors).toEqual([
      { betIndex: 1, field: 'bets[1].amount', message: 'must be less than or equal to 1000' },
      { betIndex: null, field: 'bets', message: 'contains the same selection twice' },
    ])
  })

  it.each([
    [400, 'MALFORMED_REQUEST', 'The request could not be processed. Reload the page and try again.', false],
    [409, 'INSUFFICIENT_FUNDS', 'Your balance is too low for these bets.', true],
    [409, 'BALANCE_LIMIT_EXCEEDED', 'This spin cannot be played with your current balance. Nothing was charged.', true],
    [409, 'IDEMPOTENCY_KEY_REUSED', 'This spin could not be confirmed as sent. Check your wallet history for the round before you play again.', true],
  ])('%i %s resolves the record (decided after the key lookup)', (status, code, message, refreshBalance) => {
    expect(classifySpinError(problem(status, code))).toMatchObject({
      kind: 'definitive',
      recordEffect: 'resolves',
      code,
      message,
      refreshBalance,
      unavailable: false,
    })
  })

  it.each([
    [401, 'SESSION_EXPIRED', 'Your session has ended. Sign in again to continue.'],
    [401, 'AUTHENTICATION_REQUIRED', 'Your session has ended. Sign in again to continue.'],
    [403, 'ACCESS_DENIED', 'Roulette is for player accounts.'],
    [403, 'CSRF_TOKEN_INVALID', 'Your security token could not be renewed. Reload the page and try again.'],
    [405, 'METHOD_NOT_ALLOWED', 'The server did not accept this spin. Reload the page and try again.'],
    [413, 'CONTENT_TOO_LARGE', 'The server did not accept this spin. Reload the page and try again.'],
    [415, 'UNSUPPORTED_MEDIA_TYPE', 'The server did not accept this spin. Reload the page and try again.'],
  ])('%i %s was rejected before the key lookup', (status, code, message) => {
    expect(classifySpinError(problem(status, code))).toMatchObject({ kind: 'definitive', recordEffect: 'before-lookup', message })
  })

  it('404 means no roulette on this server: the unavailable state', () => {
    expect(classifySpinError(problem(404, 'NOT_FOUND'))).toMatchObject({
      kind: 'definitive',
      recordEffect: 'before-lookup',
      unavailable: true,
      message: UNAVAILABLE_MESSAGE,
    })
  })
})

describe('§7.2: uncertain outcomes (only a retry with the same key can tell)', () => {
  it('a network error and a gateway answer without Problem Details', () => {
    for (const error of [
      new ApiError('The server could not be reached.', 0),
      new ApiError('The server is starting up', 502, '<html>Bad gateway</html>'),
      new ApiError('The server is starting up', 504, undefined),
      new ApiError('The server is starting up', 0, '<html>SPA</html>'), // a non-JSON 2xx
    ]) {
      expect(classifySpinError(error)).toMatchObject({ kind: 'uncertain', recordEffect: 'unknown', code: 'NETWORK', message: UNCERTAIN_MESSAGE })
    }
  })

  it('500 INTERNAL_ERROR, another 5xx and an unknown code or status', () => {
    for (const error of [problem(500, 'INTERNAL_ERROR'), problem(507, 'SERVER_ERROR'), problem(429, 'TOO_MANY_ATTEMPTS'), problem(400, 'SOMETHING_NEW')]) {
      expect(classifySpinError(error)).toMatchObject({ kind: 'uncertain', recordEffect: 'unknown', message: UNCERTAIN_MESSAGE })
    }
  })

  it('503 SERVICE_UNAVAILABLE is the busy wallet: retry with the same key', () => {
    expect(classifySpinError(problem(503, 'SERVICE_UNAVAILABLE'))).toMatchObject({
      kind: 'uncertain',
      recordEffect: 'unknown',
      code: 'SERVICE_UNAVAILABLE',
      message: BUSY_MESSAGE,
    })
  })

  it('anything that is not an ApiError', () => {
    expect(classifySpinError(new TypeError('boom'))).toMatchObject({ kind: 'uncertain', recordEffect: 'unknown', code: 'UNKNOWN' })
    expect(classifySpinError(undefined)).toMatchObject({ kind: 'uncertain' })
  })

  it('decides on status and code only: the same code with another status is not trusted', () => {
    expect(classifySpinError(problem(500, 'INSUFFICIENT_FUNDS')).kind).toBe('uncertain')
    expect(classifySpinError(problem(500, 'VALIDATION_FAILED')).kind).toBe('uncertain')
    const message = classifySpinError(problem(409, 'INSUFFICIENT_FUNDS', { title: 'You won!', detail: 'Retry now' })).message
    expect(message).not.toMatch(/won|Retry now/)
  })
})
