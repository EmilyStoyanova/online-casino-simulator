import { ApiError, CSRF_UNREADABLE_MESSAGE, UNAVAILABLE_MESSAGE, UNREACHABLE_MESSAGE } from '../services/apiClient'
import { msg, textMessage, type Message, type MessageKey } from './translate'

/**
 * Texts that reach the interface from outside the dictionaries, turned into messages: the field
 * messages of the backend (`errors[].message` of `400 VALIDATION_FAILED`) and the messages the API
 * client puts on an `ApiError`. Decisions never depend on these texts (the code decides on the Problem
 * Details `code` and the status); they are only translated for display.
 *
 * The backend's wording is fixed (Bean Validation messages of the DTOs and the services). A message
 * this table does not know is shown exactly as the server sent it, so nothing is ever hidden.
 */
const SERVER_FIELD_MESSAGES: readonly (readonly [RegExp, MessageKey, readonly string[]])[] = [
  [/^must not be null$/, 'server.notNull', []],
  [/^must not be blank$/, 'server.notBlank', []],
  [/^must not be empty$/, 'server.notEmpty', []],
  [/^must not contain control characters$/, 'server.noControlCharacters', []],
  [/^must be true$/, 'server.mustBeTrue', []],
  [/^must be at most (\d+) characters long$/, 'server.maxCharacters', ['max']],
  [/^must be (\d+) to (\d+) characters long$/, 'server.characterRange', ['min', 'max']],
  [/^must be at least (\d+) characters long$/, 'server.minCharacters', ['min']],
  [/^must be at most (\d+) bytes long in UTF-8$/, 'server.maxUtf8Bytes', ['max']],
  [/^must not be a commonly used password$/, 'server.commonPassword', []],
  [/^must not equal the email$/, 'server.notEmail', []],
  [/^must not equal the display name$/, 'server.notDisplayName', []],
  [/^must be a valid email address$/, 'server.validEmail', []],
  [/^must not contain the same interest twice$/, 'server.duplicateInterest', []],
  [/^must contain at most (\d+) interests$/, 'server.maxInterests', ['max']],
  [/^must be an interest code$/, 'server.interestCode', []],
  [/^must be the code of an active interest$/, 'server.activeInterest', []],
  [/^has an invalid value$/, 'server.invalidValue', []],
  [/^is not a valid cursor$/, 'server.invalidCursor', []],
  [/^must not contain the same selection twice$/, 'server.duplicateSelection', []],
  [/^must have exactly the option of its category$/, 'server.oneOption', []],
  [/^must be omitted for this category$/, 'server.omitted', []],
  [/^must be greater than or equal to (-?\d+)$/, 'server.min', ['min']],
  [/^must be less than or equal to (-?\d+)$/, 'server.max', ['max']],
  [/^size must be between (\d+) and (\d+)$/, 'server.size', ['min', 'max']],
]

/**
 * A server field message as a translatable fragment ("must be less than or equal to 1000"). Numbers
 * are kept as the server wrote them; an unknown message is shown unchanged.
 */
export function serverFieldMessage(serverText: string): Message {
  for (const [pattern, key, names] of SERVER_FIELD_MESSAGES) {
    const match = pattern.exec(serverText)
    if (match) return msg(key, Object.fromEntries(names.map((name, index) => [name, match[index + 1] ?? ''])))
  }
  return textMessage(serverText)
}

/**
 * The message of an `ApiError` for display: the API client's own texts (server unreachable, starting
 * up, unreadable security token, failed status) in the current language.
 */
export function apiErrorMessage(error: ApiError): Message {
  switch (error.message) {
    case UNREACHABLE_MESSAGE:
      return msg('api.unreachable')
    case UNAVAILABLE_MESSAGE:
      return msg('api.unavailable')
    case CSRF_UNREADABLE_MESSAGE:
      return msg('api.tokenUnreadable')
    case `Request failed with status ${error.status}`:
      return msg('api.requestFailed', { status: String(error.status) })
    default:
      return textMessage(error.message)
  }
}
