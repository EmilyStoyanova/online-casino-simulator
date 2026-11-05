import { Link } from 'react-router'
import { ghostButton, primaryButton, secondaryButton } from '../../components/buttonStyles'
import { loginPath } from './returnPath'
import { useAuth } from './useAuth'
import { useSignOut } from './useSignOut'

/** While `GET /api/v1/users/me` is pending (`checking`). */
export function CheckingSession() {
  return (
    <p className="flex items-center gap-2 text-sm text-zinc-400">
      <span aria-hidden="true" className="size-2 animate-pulse rounded-full bg-emerald-400/70" />
      Checking your sign-in status…
    </p>
  )
}

/**
 * The check failed (`unknown`): the user may still be signed in, so neither state is claimed. Offers
 * to check again, to sign in (coming back to `returnTo`) and to end a possibly existing session.
 */
export function SessionUnavailable({ returnTo }: { returnTo?: string }) {
  const { checkSession } = useAuth()
  const { signOut, pending, error } = useSignOut()

  return (
    <>
      <p className="text-sm leading-relaxed text-zinc-300">
        Your sign-in status could not be checked: the server is not reachable right now. If you
        signed in earlier, your session may still be active.
      </p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button type="button" onClick={checkSession} className={primaryButton}>
          Try again
        </button>
        <Link to={loginPath(returnTo)} className={secondaryButton}>
          Sign in
        </Link>
        <button type="button" onClick={() => void signOut()} disabled={pending} className={ghostButton}>
          {pending ? 'Signing out…' : 'Sign out'}
        </button>
      </div>
      {error && (
        <p role="alert" className="mt-3 text-sm text-rose-300">
          {error}
        </p>
      )}
    </>
  )
}
