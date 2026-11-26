import { msg, type Message } from '../../i18n/translate'

/**
 * Client-side checks that mirror the backend rules (`RegisterRequest`, `LoginRequest` in `openapi.yaml`)
 * for immediate feedback. The server stays authoritative: it validates again and its field errors are
 * shown the same way. The common-password denylist is checked only by the server (the list is not
 * shipped to the browser).
 *
 * Errors are messages (`Message`), translated when they are shown, so they follow a change of language.
 */
export type FieldErrors = Partial<Record<string, Message>>

export interface RegistrationValues {
  email: string
  displayName: string
  password: string
  adultConfirmed: boolean
}

export interface LoginValues {
  email: string
  password: string
}

export const PASSWORD_MIN_CHARACTERS = 12
export const PASSWORD_MAX_UTF8_BYTES = 72
const EMAIL_MAX_LENGTH = 254
const DISPLAY_NAME_MIN = 3
const DISPLAY_NAME_MAX = 30

export function validateRegistration(values: RegistrationValues): FieldErrors {
  const errors: FieldErrors = {}
  const email = values.email.trim()
  const displayName = values.displayName.trim()

  const emailError = validateEmail(email)
  if (emailError) errors.email = emailError

  const displayNameError = validateDisplayName(displayName)
  if (displayNameError) errors.displayName = displayNameError

  const password = values.password
  if (password === '') {
    errors.password = msg('validation.passwordRequired')
  } else if ([...password].length < PASSWORD_MIN_CHARACTERS) {
    errors.password = msg('validation.passwordMin', { min: PASSWORD_MIN_CHARACTERS })
  } else if (new TextEncoder().encode(password).length > PASSWORD_MAX_UTF8_BYTES) {
    errors.password = msg('validation.passwordMaxBytes', { max: PASSWORD_MAX_UTF8_BYTES })
  } else if (sameIgnoringCase(password, email)) {
    errors.password = msg('validation.passwordIsEmail')
  } else if (sameIgnoringCase(password, displayName)) {
    errors.password = msg('validation.passwordIsDisplayName')
  }

  if (!values.adultConfirmed) {
    errors.adultConfirmed = msg('validation.adult')
  }
  return errors
}

export function validateLogin(values: LoginValues): FieldErrors {
  const errors: FieldErrors = {}
  if (values.email.trim() === '') errors.email = msg('validation.emailRequired')
  if (values.password === '') errors.password = msg('validation.loginPasswordRequired')
  return errors
}

/** Registration and profile (`displayName` of `RegisterRequest` and `UpdateProfileRequest`); trims first. */
export function validateDisplayName(value: string): Message | undefined {
  const displayName = value.trim()
  if (displayName === '') return msg('validation.displayNameRequired')
  if (displayName.length < DISPLAY_NAME_MIN || displayName.length > DISPLAY_NAME_MAX) {
    return msg('validation.displayNameLength', { min: DISPLAY_NAME_MIN, max: DISPLAY_NAME_MAX })
  }
  if (/\p{Cc}/u.test(displayName)) return msg('validation.controlCharacters')
  return undefined
}

function validateEmail(email: string): Message | undefined {
  if (email === '') return msg('validation.emailRequired')
  if (email.length > EMAIL_MAX_LENGTH) return msg('validation.emailMax', { max: EMAIL_MAX_LENGTH })
  // Same restriction as the server: only ASCII is accepted.
  if (!/^[\x21-\x7e]+$/.test(email)) return msg('validation.emailAscii')
  if (!/^[^@]+@[^@]+$/.test(email)) return msg('validation.emailFormat')
  return undefined
}

function sameIgnoringCase(password: string, other: string): boolean {
  return other !== '' && password.trim().toLowerCase() === other.trim().toLowerCase()
}

export function hasErrors(errors: FieldErrors): boolean {
  return Object.values(errors).some((message) => message !== undefined)
}
