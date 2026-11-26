import { useState, type FormEvent } from 'react'
import { FormAlert } from '../../components/form/FormAlert'
import { PasswordField } from '../../components/form/PasswordField'
import { TextField } from '../../components/form/TextField'
import { primaryButton } from '../../components/buttonStyles'
import { useI18n } from '../../i18n/useI18n'
import { useAuth } from './useAuth'
import { useAuthForm } from './useAuthForm'
import { validateLogin } from './validation'

/** Sign-in form (`POST /api/v1/auth/login`). Calls `onSuccess` once the user is signed in. */
export function LoginForm({ onSuccess }: { onSuccess: () => void }) {
  const { login } = useAuth()
  const { t } = useI18n()
  const { formRef, fieldErrors, message, submitting, locked, submit, clearFieldError } = useAuthForm('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const fieldError = (field: string) => {
    const error = fieldErrors[field]
    return error && t(error)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void submit(validateLogin({ email, password }), async () => {
      await login({ email, password })
      onSuccess()
    })
  }

  return (
    <form ref={formRef} onSubmit={handleSubmit} noValidate aria-busy={submitting} className="space-y-5">
      {message && <FormAlert tone="error">{t(message)}</FormAlert>}

      <TextField
        id="login-email"
        label={t('common.email')}
        type="email"
        autoComplete="email"
        inputMode="email"
        value={email}
        onChange={(event) => {
          setEmail(event.target.value)
          clearFieldError('email')
        }}
        error={fieldError('email')}
        disabled={submitting}
      />
      <PasswordField
        id="login-password"
        label={t('common.password')}
        autoComplete="current-password"
        value={password}
        onChange={(event) => {
          setPassword(event.target.value)
          clearFieldError('password')
        }}
        error={fieldError('password')}
        disabled={submitting}
      />

      <button type="submit" disabled={submitting || locked} className={`${primaryButton} w-full`}>
        {submitting ? t('login.submitting') : t('common.signIn')}
      </button>
    </form>
  )
}
