/**
 * Client-side checks that mirror the backend rules (`RegisterRequest`, `LoginRequest` in `openapi.yaml`)
 * for immediate feedback. The server stays authoritative: it validates again and its field errors are
 * shown the same way.
 */
export type FieldErrors = Partial<Record<string, string>>

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

  if (displayName === '') {
    errors.displayName = 'Enter a display name.'
  } else if (displayName.length < DISPLAY_NAME_MIN || displayName.length > DISPLAY_NAME_MAX) {
    errors.displayName = `Use ${DISPLAY_NAME_MIN} to ${DISPLAY_NAME_MAX} characters.`
  } else if (/\p{Cc}/u.test(displayName)) {
    errors.displayName = 'Remove control characters such as tabs or line breaks.'
  }

  const password = values.password
  if (password === '') {
    errors.password = 'Enter a password.'
  } else if ([...password].length < PASSWORD_MIN_CHARACTERS) {
    errors.password = `Use at least ${PASSWORD_MIN_CHARACTERS} characters.`
  } else if (new TextEncoder().encode(password).length > PASSWORD_MAX_UTF8_BYTES) {
    errors.password = `Use at most ${PASSWORD_MAX_UTF8_BYTES} bytes (fewer characters if you use accents or emoji).`
  } else if (sameIgnoringCase(password, email)) {
    errors.password = 'Your password must not be your email address.'
  } else if (sameIgnoringCase(password, displayName)) {
    errors.password = 'Your password must not be your display name.'
  }

  if (!values.adultConfirmed) {
    errors.adultConfirmed = 'Confirm that you are at least 18 years old.'
  }
  return errors
}

export function validateLogin(values: LoginValues): FieldErrors {
  const errors: FieldErrors = {}
  if (values.email.trim() === '') errors.email = 'Enter your email address.'
  if (values.password === '') errors.password = 'Enter your password.'
  return errors
}

function validateEmail(email: string): string | undefined {
  if (email === '') return 'Enter your email address.'
  if (email.length > EMAIL_MAX_LENGTH) return `Use at most ${EMAIL_MAX_LENGTH} characters.`
  // Same restriction as the server: only ASCII is accepted.
  if (!/^[\x21-\x7e]+$/.test(email)) return 'Use only Latin letters, digits and common symbols.'
  if (!/^[^@]+@[^@]+$/.test(email)) return 'Enter a valid email address, e.g. alice@example.com.'
  return undefined
}

function sameIgnoringCase(password: string, other: string): boolean {
  return other !== '' && password.trim().toLowerCase() === other.trim().toLowerCase()
}

export function hasErrors(errors: FieldErrors): boolean {
  return Object.values(errors).some((message) => message !== undefined)
}
