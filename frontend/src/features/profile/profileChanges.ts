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

/** How a failed save is shown. The draft is kept in every case. */
export type SaveFailure =
  | { kind: 'invalid'; fieldErrors: FieldErrors }
  | { kind: 'conflict' }
  | { kind: 'error'; message: string; retry: boolean }

const KEPT = 'Your changes have not been saved, but they are still in the form.'

/** Decides on the Problem Details `code` and the status only, never on the text. */
export function describeSaveFailure(error: unknown): SaveFailure {
  if (!(error instanceof ApiError)) {
    return { kind: 'error', message: `Something unexpected happened. ${KEPT}`, retry: true }
  }
  if (error.isUnavailable) {
    return { kind: 'error', message: `${error.message} ${KEPT}`, retry: true }
  }
  switch (error.code) {
    case 'VALIDATION_FAILED':
      return { kind: 'invalid', fieldErrors: interestErrorsTogether(serverFieldErrors(error)) }
    case 'CONCURRENT_MODIFICATION':
      return { kind: 'conflict' }
    case 'CSRF_TOKEN_INVALID':
      // The client already fetched a new token and tried once more (apiClient).
      return {
        kind: 'error',
        message: `Your security token could not be renewed. ${KEPT} Try again, or reload the page if it keeps failing.`,
        retry: true,
      }
    case 'SESSION_EXPIRED':
    case 'AUTHENTICATION_REQUIRED':
      // The sign-in page takes over (AuthProvider); shown only until then.
      return { kind: 'error', message: 'Your session has ended. Sign in again to continue.', retry: false }
    case 'MALFORMED_REQUEST':
      return { kind: 'error', message: 'The request could not be processed. Reload the page and try again.', retry: false }
    default:
      return { kind: 'error', message: `Saving failed on our side. ${KEPT} Please try again in a moment.`, retry: true }
  }
}

/** The server reports unknown or inactive codes per position (`interests[2]`); the form has one picker. */
function interestErrorsTogether(errors: FieldErrors): FieldErrors {
  const result: FieldErrors = {}
  const interestMessages = new Set<string>()
  for (const [field, message] of Object.entries(errors)) {
    if (message === undefined) continue
    if (field === 'interests' || field.startsWith('interests[')) {
      interestMessages.add(message)
    } else {
      result[field] = message
    }
  }
  if (interestMessages.size > 0) {
    result.interests = [...interestMessages].join(' ')
  }
  return result
}
