import { Link, Navigate, useNavigate } from 'react-router'
import { FormAlert } from '../components/form/FormAlert'
import { AuthCard } from '../components/layout/AuthCard'
import { LoginForm } from '../features/auth/LoginForm'
import { registerPath, useReturnPath } from '../features/auth/returnPath'
import { useAuth } from '../features/auth/useAuth'
import { useI18n } from '../i18n/useI18n'

const linkClasses = 'font-semibold text-emerald-300 underline-offset-2 hover:underline'

export function LoginPage() {
  const { state } = useAuth()
  const { t } = useI18n()
  const navigate = useNavigate()
  // The page that sent the user here (`RequireAuth`), checked to be internal.
  const returnTo = useReturnPath()

  if (state.status === 'authenticated') {
    return <Navigate to={returnTo} replace />
  }

  const expired = state.status === 'anonymous' && state.reason === 'session-expired'
  return (
    <AuthCard
      title={t('login.title')}
      description={t('login.description')}
      footer={
        <>
          {t('login.newHere')}{' '}
          <Link to={registerPath(returnTo)} className={linkClasses}>
            {t('login.createAccountLink')}
          </Link>
        </>
      }
    >
      {expired && (
        <div className="mb-5">
          <FormAlert tone="info">{t('session.ended')}</FormAlert>
        </div>
      )}
      {!expired && returnTo !== '/' && (
        <div className="mb-5">
          <FormAlert tone="info">{t('login.required')}</FormAlert>
        </div>
      )}
      <LoginForm onSuccess={() => navigate(returnTo, { replace: true })} />
    </AuthCard>
  )
}
