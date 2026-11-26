import { describe, expect, it } from 'vitest'
import { createI18n, type Message } from '../../i18n/translate'
import { ApiError } from '../../services/apiClient'
import { alice } from '../../test/fixtures'
import { describeSaveFailure, profileChanges } from './profileChanges'

const saved = { ...alice, displayName: 'Alice', interests: ['PROBABILITY', 'STATISTICS'] }

/** Messages as the English and the Bulgarian interface show them. */
const shown = (locale: 'en' | 'bg', messages: Partial<Record<string, Message>>) =>
  Object.fromEntries(Object.entries(messages).map(([field, message]) => [field, message && createI18n(locale).t(message)]))

function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return new ApiError(`Request failed with status ${status}`, status, { title: 'Error', status, code, ...extra })
}

describe('profileChanges', () => {
  it('contains only what differs from the saved profile', () => {
    expect(profileChanges(saved, { displayName: 'Alice', interests: ['PROBABILITY', 'STATISTICS'] })).toEqual({})
    expect(profileChanges(saved, { displayName: '  Alice B. ', interests: saved.interests })).toEqual({ displayName: 'Alice B.' })
    expect(profileChanges(saved, { displayName: 'Alice', interests: [] })).toEqual({ interests: [] })
  })

  it('compares the display name trimmed and the interests as a set', () => {
    expect(profileChanges(saved, { displayName: ' Alice\t', interests: ['STATISTICS', 'PROBABILITY'] })).toEqual({})
    expect(profileChanges(saved, { displayName: 'Alice', interests: ['PROBABILITY', 'HISTORY'] })).toEqual({
      interests: ['PROBABILITY', 'HISTORY'],
    })
  })
})

describe('describeSaveFailure', () => {
  it('maps field errors, reporting every interest position on the picker', () => {
    const error = problem(400, 'VALIDATION_FAILED', {
      errors: [
        { field: 'displayName', message: 'must be 3 to 30 characters long' },
        { field: 'interests[0]', message: 'must be an active interest' },
        { field: 'interests[2]', message: 'must be an active interest' },
      ],
    })

    const failure = describeSaveFailure(error)
    expect(failure.kind).toBe('invalid')
    const fieldErrors = failure.kind === 'invalid' ? failure.fieldErrors : {}
    expect(shown('en', fieldErrors)).toEqual({
      displayName: 'Must be 3 to 30 characters long.',
      interests: 'Must be an active interest.',
    })
    // A known server message is translated; one the dictionary does not know is shown as sent.
    expect(shown('bg', fieldErrors)).toEqual({
      displayName: 'Трябва да е от 3 до 30 знака.',
      interests: 'Must be an active interest.',
    })
  })

  it('recognises a conflict and keeps retry for temporary failures only', () => {
    expect(describeSaveFailure(problem(409, 'CONCURRENT_MODIFICATION'))).toEqual({ kind: 'conflict' })
    expect(describeSaveFailure(problem(403, 'CSRF_TOKEN_INVALID'))).toMatchObject({ kind: 'error', retry: true })
    expect(describeSaveFailure(problem(500, 'INTERNAL_ERROR'))).toMatchObject({ kind: 'error', retry: true })
    const unreachable = describeSaveFailure(new ApiError('The server could not be reached.', 0))
    expect(unreachable).toMatchObject({ kind: 'error', retry: true })
    expect(unreachable.kind === 'error' && createI18n('en').t(unreachable.message)).toContain('still in the form')
    expect(describeSaveFailure(problem(401, 'SESSION_EXPIRED'))).toMatchObject({ kind: 'error', retry: false })
    expect(describeSaveFailure(problem(400, 'MALFORMED_REQUEST'))).toMatchObject({ kind: 'error', retry: false })
  })
})
