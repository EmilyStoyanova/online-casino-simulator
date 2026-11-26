import { apiErrorMessage } from '../../i18n/serverMessages'
import { msg, type Message } from '../../i18n/translate'
import { ApiError } from '../../services/apiClient'
import type { UpdateProfileRequest, UserProfile } from '../../types/user'
import { serverFieldErrors } from '../auth/authErrors'
import type { FieldErrors } from '../auth/validation'

/** At most this many interests per profile (`UpdateProfileRequest.interests.maxItems`). */
export const MAX_INTERESTS = 8

export interface ProfileDraft {
  displayName: string
  interests: readonly string[]
}

/**
 * Only what differs from the saved profile, so a save never sends a value the user did not change
 * (e.g. the interests while their catalogue could not be loaded). The display name is compared
 * trimmed, as the server stores it; the interests as a set, because the server keeps catalogue order.
 */
export function profileChanges(saved: UserProfile, draft: ProfileDraft): UpdateProfileRequest {
  const changes: UpdateProfileRequest = {}
  const displayName = draft.displayName.trim()
  if (displayName !== saved.displayName) {
    changes.displayName = displayName
  }
  if (!sameSelection(draft.interests, saved.interests)) {
    changes.interests = [...draft.interests]
  }
  return changes
}

/** The same interests, ignoring order. */
export function sameSelection(a: readonly string[], b: readonly string[]): boolean {
  const set = new Set(a)
  return set.size === new Set(b).size && b.every((code) => set.has(code))
}

/** How a failed save is shown. The draft is kept in every case. Messages are translated when shown. */
export type SaveFailure =
  | { kind: 'invalid'; fieldErrors: FieldErrors }
  | { kind: 'conflict' }
  | { kind: 'error'; message: Message; retry: boolean }

/** Decides on the Problem Details `code` and the status only, never on the text. */
export function describeSaveFailure(error: unknown): SaveFailure {
  if (!(error instanceof ApiError)) {
    return { kind: 'error', message: msg('profile.error.unexpected'), retry: true }
  }
  if (error.isUnavailable) {
    return { kind: 'error', message: msg('profile.error.unavailable', { reason: apiErrorMessage(error) }), retry: true }
  }
  switch (error.code) {
    case 'VALIDATION_FAILED':
      return { kind: 'invalid', fieldErrors: interestErrorsTogether(serverFieldErrors(error)) }
    case 'CONCURRENT_MODIFICATION':
      return { kind: 'conflict' }
    case 'CSRF_TOKEN_INVALID':
      // The client already fetched a new token and tried once more (apiClient).
      return { kind: 'error', message: msg('profile.error.csrf'), retry: true }
    case 'SESSION_EXPIRED':
    case 'AUTHENTICATION_REQUIRED':
      // The sign-in page takes over (AuthProvider); shown only until then.
      return { kind: 'error', message: msg('session.ended'), retry: false }
    case 'MALFORMED_REQUEST':
      return { kind: 'error', message: msg('error.malformed'), retry: false }
    default:
      return { kind: 'error', message: msg('profile.error.server'), retry: true }
  }
}

/** The server reports unknown or inactive codes per position (`interests[2]`); the form has one picker. */
function interestErrorsTogether(errors: FieldErrors): FieldErrors {
  const result: FieldErrors = {}
  // Equal messages for several positions are shown once (compared by content).
  const interestMessages = new Map<string, Message>()
  for (const [field, message] of Object.entries(errors)) {
    if (message === undefined) continue
    if (field === 'interests' || field.startsWith('interests[')) {
      interestMessages.set(JSON.stringify(message), message)
    } else {
      result[field] = message
    }
  }
  const [first, ...others] = interestMessages.values()
  if (first !== undefined) {
    result.interests = others.reduce<Message>((joined, next) => msg('common.join', { first: joined, second: next }), first)
  }
  return result
}
