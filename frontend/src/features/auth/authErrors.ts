import { ApiError } from '../../services/apiClient'
import type { FieldErrors } from './validation'

export interface FormFailure {
  /** Shown in the alert above the form. */
  message: string
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
    return { message: 'Something unexpected happened. Please try again.', fieldErrors: {} }
  }
  if (error.isNetworkError) {
    return { message: error.message, fieldErrors: {} }
  }
  switch (error.code) {
    case 'VALIDATION_FAILED':
      return { message: 'Please correct the highlighted fields.', fieldErrors: serverFieldErrors(error) }
    case 'INVALID_CREDENTIALS':
      return {
        message: 'The email or password is incorrect. Accounts that have been disabled cannot sign in.',
        fieldErrors: {},
      }
    case 'EMAIL_ALREADY_REGISTERED':
      return {
        message: 'An account with this email already exists. Sign in instead.',
        fieldErrors: { email: 'An account with this email already exists.' },
      }
    case 'TOO_MANY_ATTEMPTS': {
      const attempts = action === 'login' ? 'failed sign-in attempts' : 'registration attempts'
      const wait = error.retryAfterSeconds === undefined ? 'later' : `in ${formatWait(error.retryAfterSeconds)}`
      return {
        message: `Too many ${attempts} from this network. Try again ${wait}.`,
        fieldErrors: {},
        retryAfterSeconds: error.retryAfterSeconds,
      }
    }
    case 'REGISTRATION_DISABLED':
      return { message: 'New registrations are currently closed. Existing accounts can still sign in.', fieldErrors: {} }
    case 'CSRF_TOKEN_INVALID':
      return { message: 'Your security token could not be renewed. Reload the page and try again.', fieldErrors: {} }
    case 'MALFORMED_REQUEST':
      return { message: 'The request could not be processed. Reload the page and try again.', fieldErrors: {} }
    default:
      return { message: 'Something went wrong on our side. Please try again in a moment.', fieldErrors: {} }
  }
}

/** E.g. "15 minutes", "1 minute", "45 seconds". */
export function formatWait(seconds: number): string {
  if (seconds < 60) {
    return `${seconds} ${seconds === 1 ? 'second' : 'seconds'}`
  }
  const minutes = Math.ceil(seconds / 60)
  return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`
}

/** Server messages are lower-case fragments ("must be at least 12 characters long"). */
export function serverFieldErrors(error: ApiError): FieldErrors {
  const errors: FieldErrors = {}
  for (const { field, message } of error.problem?.errors ?? []) {
    const sentence = `${message.charAt(0).toUpperCase()}${message.slice(1)}.`
    errors[field] = errors[field] === undefined ? sentence : `${errors[field]} ${sentence}`
  }
  return errors
}
