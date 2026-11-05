import { Link, Navigate, useNavigate } from 'react-router'
import { AuthCard } from '../components/layout/AuthCard'
import { RegisterForm } from '../features/auth/RegisterForm'
import { loginPath, useReturnPath } from '../features/auth/returnPath'
import { useAuth } from '../features/auth/useAuth'

const linkClasses = 'font-semibold text-emerald-300 underline-offset-2 hover:underline'

export function RegisterPage() {
  const { state } = useAuth()
  const navigate = useNavigate()
  // Kept from the login page, so a new account also comes back to the requested page.
  const returnTo = useReturnPath()

  if (state.status === 'authenticated') {
    return <Navigate to={returnTo} replace />
  }

  return (
    <AuthCard
      title="Create your account"
      description={
        <p className="rounded-lg border border-amber-400/30 bg-amber-400/10 px-3.5 py-3 text-amber-200">
          Virtual coins only: they have no monetary value and cannot be bought, withdrawn or exchanged
          for prizes.
        </p>
      }
      footer={
        <>
          Already have an account?{' '}
          <Link to={loginPath(returnTo)} className={linkClasses}>
            Sign in
          </Link>
        </>
      }
    >
      {/* Registration signs the user in, so the account is shown right away. */}
      <RegisterForm onSuccess={() => navigate(returnTo, { replace: true })} />
    </AuthCard>
  )
}
