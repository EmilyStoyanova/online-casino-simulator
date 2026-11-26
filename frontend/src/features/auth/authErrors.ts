import { apiErrorMessage, serverFieldMessage } from '../../i18n/serverMessages'
import { msg, type Message } from '../../i18n/translate'
import { ApiError } from '../../services/apiClient'
import type { FieldErrors } from './validation'

export interface FormFailure {
  /** Shown in the alert above the form. */
  message: Message
  /** Shown next to the fields, by property name of the request. */
  fieldErrors: FieldErrors
  /** From `Retry-After` of `429 TOO_MANY_ATTEMPTS`; the form stays disabled meanwhile. */
  retryAfterSeconds?: number
  /** The Problem Details `code`, if the server sent one. */
  code?: string
}

/** Turns a failed registration or login into messages. Decides on the Problem Details `code` only. */
export function describeAuthFailure(error: unknown, action: 'login' | 'register'): FormFailure {
  const failure = describe(error, action)
  return error instanceof ApiError && error.code !== undefined ? { ...failure, code: error.code } : failure
}

function describe(error: unknown, action: 'login' | 'register'): FormFailure {
  if (!(error instanceof ApiError)) {
    return { message: msg('error.unexpected'), fieldErrors: {} }
  }
  if (error.isUnavailable) {
    return { message: apiErrorMessage(error), fieldErrors: {} }
  }
  switch (error.code) {
    case 'VALIDATION_FAILED':
      return { message: msg('error.correctFields'), fieldErrors: serverFieldErrors(error) }
    case 'INVALID_CREDENTIALS':
      return { message: msg('auth.error.invalidCredentials'), fieldErrors: {} }
    case 'EMAIL_ALREADY_REGISTERED':
      return { message: msg('auth.error.emailTaken'), fieldErrors: { email: msg('auth.error.emailTakenField') } }
    case 'TOO_MANY_ATTEMPTS': {
      const seconds = error.retryAfterSeconds
      const message =
        seconds === undefined
          ? msg(action === 'login' ? 'auth.error.tooManyLogin' : 'auth.error.tooManyRegister')
          : msg(action === 'login' ? 'auth.error.tooManyLoginWait' : 'auth.error.tooManyRegisterWait', {
              wait: formatWait(seconds),
            })
      return { message, fieldErrors: {}, retryAfterSeconds: seconds }
    }
    case 'REGISTRATION_DISABLED':
      return { message: msg('auth.error.registrationDisabled'), fieldErrors: {} }
    case 'CSRF_TOKEN_INVALID':
      return { message: msg('error.csrf'), fieldErrors: {} }
    case 'MALFORMED_REQUEST':
      return { message: msg('error.malformed'), fieldErrors: {} }
    default:
      return { message: msg('error.serverSide'), fieldErrors: {} }
  }
}

/** E.g. "15 minutes", "1 minute", "45 seconds" (as a message, so it follows the language). */
export function formatWait(seconds: number): Message {
  if (seconds < 60) return msg('time.seconds', { count: seconds })
  return msg('time.minutes', { count: Math.ceil(seconds / 60) })
}

/**
 * Server messages are lower-case fragments ("must be at least 12 characters long"): each is shown as a
 * sentence, translated when the language has the message, several for one field one after the other.
 */
export function serverFieldErrors(error: ApiError): FieldErrors {
  const errors: FieldErrors = {}
  for (const { field, message } of error.problem?.errors ?? []) {
    const sentence: Message = { sentence: serverFieldMessage(message) }
    const previous = errors[field]
    errors[field] = previous === undefined ? sentence : msg('common.join', { first: previous, second: sentence })
  }
  return errors
}
