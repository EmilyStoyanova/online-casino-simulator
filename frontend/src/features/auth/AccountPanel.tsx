import { Link } from 'react-router'
import { DetailRow as Row } from '../../components/DetailRow'
import { primaryButton, secondaryButton } from '../../components/buttonStyles'
import type { UserProfile } from '../../types/user'
import { formatDate, roleLabel } from './profileFormat'
import { CheckingSession, SessionUnavailable } from './SessionStatus'
import { useAuth } from './useAuth'
import { useSignOut } from './useSignOut'

function SignedIn({ user }: { user: UserProfile }) {
  const { signOut, pending, error } = useSignOut()

  return (
    <>
      <p className="mb-3 text-sm text-zinc-300">
        Signed in as <span className="font-semibold text-emerald-300">{user.displayName}</span>
      </p>
      <dl className="divide-y divide-zinc-800">
        <Row label="Email">{user.email}</Row>
        <Row label="Role">{roleLabel(user.role)}</Row>
        <Row label="Member since">{formatDate(user.createdAt)}</Row>
        <Row label="18+ confirmed">{formatDate(user.adultConfirmedAt)}</Row>
      </dl>
      <p className="mt-4 text-xs leading-relaxed text-zinc-500">
        Your session is kept in a secure cookie. It ends after 30 minutes without activity, 12 hours
        after signing in, or when you sign out.
      </p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Link to="/profile" className={primaryButton}>
          Edit profile
        </Link>
        <button type="button" onClick={() => void signOut()} disabled={pending} className={secondaryButton}>
          {pending ? 'Signing out…' : 'Sign out'}
        </button>
        {error && (
          <p role="alert" className="text-sm text-rose-300">
            {error}
          </p>
        )}
      </div>
    </>
  )
}

function SignedOut({ expired }: { expired: boolean }) {
  return (
    <>
      <p className="text-sm leading-relaxed text-zinc-300">
        {expired ? 'Your session has ended. Sign in again to continue.' : 'You are signed out.'} New here?
        Create a free account in a minute.
      </p>
      <div className="mt-4 flex flex-wrap gap-3">
        <Link to="/register" className={primaryButton}>
          Create account
        </Link>
        <Link to="/login" className={secondaryButton}>
          Sign in
        </Link>
      </div>
    </>
  )
}

/** The user's account on the home page: profile data when signed in, otherwise how to sign in. */
export function AccountPanel() {
  const { state } = useAuth()

  return (
    <section
      aria-labelledby="account-heading"
      className="w-full rounded-2xl border border-zinc-800 bg-zinc-900/70 p-6 text-left shadow-xl"
    >
      <h2 id="account-heading" className="mb-4 text-lg font-semibold text-zinc-100">
        Your account
      </h2>
      <div aria-live="polite" aria-busy={state.status === 'checking'}>
        {state.status === 'checking' && <CheckingSession />}
        {state.status === 'authenticated' && <SignedIn user={state.user} />}
        {state.status === 'unknown' && <SessionUnavailable />}
        {state.status === 'anonymous' && <SignedOut expired={state.reason === 'session-expired'} />}
      </div>
    </section>
  )
}
