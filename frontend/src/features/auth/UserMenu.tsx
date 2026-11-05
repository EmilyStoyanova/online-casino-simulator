import { NavLink } from 'react-router'
import { ghostButton, primaryButton, secondaryButton } from '../../components/buttonStyles'
import { useAuth } from './useAuth'
import { useSignOut } from './useSignOut'

const compact = 'px-3 py-1.5'

/**
 * Header navigation: for the signed-in user "My profile", the display name and "Sign out", otherwise
 * "Sign in" and "Create account".
 */
export function UserMenu() {
  const { state } = useAuth()
  const { signOut, pending, error } = useSignOut()

  if (state.status === 'authenticated') {
    const { displayName } = state.user
    return (
      <div className="flex flex-wrap items-center justify-end gap-x-3 gap-y-2">
        {/* NavLink sets aria-current="page" on the profile page. */}
        <NavLink to="/profile" className={`${ghostButton} ${compact} aria-[current=page]:bg-zinc-800 aria-[current=page]:text-zinc-50`}>
          My profile
        </NavLink>
        <span className="flex min-w-0 items-center gap-2 text-sm">
          <span
            aria-hidden="true"
            className="grid size-8 shrink-0 place-items-center rounded-full bg-emerald-500/15 font-semibold uppercase text-emerald-300 ring-1 ring-emerald-500/40"
          >
            {displayName.charAt(0)}
          </span>
          <span className="max-w-40 truncate font-medium text-zinc-100">
            <span className="sr-only">Signed in as </span>
            {displayName}
          </span>
        </span>
        <button type="button" onClick={() => void signOut()} disabled={pending} className={`${secondaryButton} ${compact}`}>
          {pending ? 'Signing out…' : 'Sign out'}
        </button>
        {error && (
          <p role="alert" className="w-full text-right text-xs text-rose-300">
            {error}
          </p>
        )}
      </div>
    )
  }

  if (state.status === 'checking') {
    return (
      <span className="flex items-center gap-2 text-sm text-zinc-400">
        <span aria-hidden="true" className="size-2 animate-pulse rounded-full bg-emerald-400/70" />
        Checking sign-in…
      </span>
    )
  }

  // Also while the state is unknown: "Sign in" is always possible; the account panel offers "Sign out".
  return (
    <div className="flex items-center gap-2">
      <NavLink to="/login" className={`${ghostButton} ${compact}`}>
        Sign in
      </NavLink>
      <NavLink to="/register" className={`${primaryButton} ${compact}`}>
        Create account
      </NavLink>
    </div>
  )
}
