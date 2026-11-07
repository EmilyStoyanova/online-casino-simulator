import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { CheckboxField } from '../../components/form/CheckboxField'
import { FormAlert } from '../../components/form/FormAlert'
import { PasswordField } from '../../components/form/PasswordField'
import { TextField } from '../../components/form/TextField'
import { primaryButton } from '../../components/buttonStyles'
import { useAuth } from './useAuth'
import { useAuthForm } from './useAuthForm'
import { PASSWORD_MAX_UTF8_BYTES, PASSWORD_MIN_CHARACTERS, validateRegistration } from './validation'

/**
 * Registration form (`POST /api/v1/auth/register`). The server signs the new user in, so `onSuccess`
 * is called with a session already in place.
 */
export function RegisterForm({ onSuccess }: { onSuccess: () => void }) {
  const { register } = useAuth()
  const { formRef, fieldErrors, message, failureCode, submitting, locked, submit, clearFieldError } =
    useAuthForm('register')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [adultConfirmed, setAdultConfirmed] = useState(false)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const values = { email, displayName, password, adultConfirmed }
    void submit(validateRegistration(values), async () => {
      await register(values)
      onSuccess()
    })
  }

  return (
    <form ref={formRef} onSubmit={handleSubmit} noValidate aria-busy={submitting} className="space-y-5">
      {message && (
        <FormAlert tone="error">
          {message}{' '}
          {(failureCode === 'EMAIL_ALREADY_REGISTERED' || failureCode === 'REGISTRATION_DISABLED') && (
            <Link to="/login" className="font-semibold text-rose-100 underline underline-offset-2">
              Go to sign in
            </Link>
          )}
        </FormAlert>
      )}

      <TextField
        id="register-email"
        label="Email"
        type="email"
        autoComplete="email"
        inputMode="email"
        maxLength={254}
        value={email}
        onChange={(event) => {
          setEmail(event.target.value)
          clearFieldError('email')
        }}
        error={fieldErrors.email}
        disabled={submitting}
      />
      <TextField
        id="register-display-name"
        label="Display name"
        autoComplete="nickname"
        maxLength={30}
        hint="3 to 30 characters. Shown to other players."
        value={displayName}
        onChange={(event) => {
          setDisplayName(event.target.value)
          clearFieldError('displayName')
        }}
        error={fieldErrors.displayName}
        disabled={submitting}
      />
      <PasswordField
        id="register-password"
        label="Password"
        autoComplete="new-password"
        hint={`At least ${PASSWORD_MIN_CHARACTERS} characters (at most ${PASSWORD_MAX_UTF8_BYTES} bytes), different from your email and display name, and not a commonly used password. A passphrase of a few words works well.`}
        value={password}
        onChange={(event) => {
          setPassword(event.target.value)
          clearFieldError('password')
        }}
        error={fieldErrors.password}
        disabled={submitting}
      />
      <CheckboxField
        id="register-adult"
        label="I confirm that I am at least 18 years old."
        checked={adultConfirmed}
        onChange={(event) => {
          setAdultConfirmed(event.target.checked)
          clearFieldError('adultConfirmed')
        }}
        error={fieldErrors.adultConfirmed}
        disabled={submitting}
      />

      <button type="submit" disabled={submitting || locked} className={`${primaryButton} w-full`}>
        {submitting ? 'Creating account…' : 'Create account'}
      </button>
    </form>
  )
}
