import { Link, Navigate, useNavigate } from 'react-router'
import { FormAlert } from '../components/form/FormAlert'
import { AuthCard } from '../components/layout/AuthCard'
import { LoginForm } from '../features/auth/LoginForm'
import { registerPath, useReturnPath } from '../features/auth/returnPath'
import { useAuth } from '../features/auth/useAuth'

const linkClasses = 'font-semibold text-emerald-300 underline-offset-2 hover:underline'

export function LoginPage() {
  const { state } = useAuth()
  const navigate = useNavigate()
  // The page that sent the user here (`RequireAuth`), checked to be internal.
  const returnTo = useReturnPath()

  if (state.status === 'authenticated') {
    return <Navigate to={returnTo} replace />
  }

  const expired = state.status === 'anonymous' && state.reason === 'session-expired'
  return (
    <AuthCard
      title="Sign in"
      description="Welcome back. Sign in to continue learning with virtual coins."
      footer={
        <>
          New here?{' '}
          <Link to={registerPath(returnTo)} className={linkClasses}>
            Create an account
          </Link>
        </>
      }
    >
      {expired && (
        <div className="mb-5">
          <FormAlert tone="info">Your session has ended. Sign in again to continue.</FormAlert>
        </div>
      )}
      {!expired && returnTo !== '/' && (
        <div className="mb-5">
          <FormAlert tone="info">Please sign in to open this page.</FormAlert>
        </div>
      )}
      <LoginForm onSuccess={() => navigate(returnTo, { replace: true })} />
    </AuthCard>
  )
}
