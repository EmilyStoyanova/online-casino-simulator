import { Link, Navigate, useNavigate } from 'react-router'
import { FormAlert } from '../components/form/FormAlert'
import { AuthCard } from '../components/layout/AuthCard'
import { LoginForm } from '../features/auth/LoginForm'
import { useAuth } from '../features/auth/useAuth'

const linkClasses = 'font-semibold text-emerald-300 underline-offset-2 hover:underline'

export function LoginPage() {
  const { state } = useAuth()
  const navigate = useNavigate()

  if (state.status === 'authenticated') {
    return <Navigate to="/" replace />
  }

  return (
    <AuthCard
      title="Sign in"
      description="Welcome back. Sign in to continue learning with virtual coins."
      footer={
        <>
          New here?{' '}
          <Link to="/register" className={linkClasses}>
            Create an account
          </Link>
        </>
      }
    >
      {state.status === 'anonymous' && state.reason === 'session-expired' && (
        <div className="mb-5">
          <FormAlert tone="info">Your session has ended. Sign in again to continue.</FormAlert>
        </div>
      )}
      <LoginForm onSuccess={() => navigate('/', { replace: true })} />
    </AuthCard>
  )
}
