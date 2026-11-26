import { NavLink } from 'react-router'
import { useAuth } from '../../features/auth/useAuth'

const destinations = [
  { to: '/', label: 'Lobby', end: true },
  { to: '/roulette', label: 'Roulette', end: false },
  { to: '/wallet', label: 'Wallet', end: false },
] as const

const link =
  'rounded-lg px-3 py-2 text-center text-sm font-medium text-zinc-300 transition hover:bg-zinc-800/70 hover:text-zinc-100 ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400 ' +
  'aria-[current=page]:bg-emerald-500/15 aria-[current=page]:text-emerald-200 aria-[current=page]:ring-1 aria-[current=page]:ring-emerald-500/40'

/**
 * The main navigation of the player experience: Lobby, Roulette, Wallet. Rendered only for a confirmed
 * `PLAYER` session; visitors, administrators and an unconfirmed session (`checking`, `unknown`) get
 * none, so no link leads to a page they cannot use. `NavLink` marks the current destination with
 * `aria-current="page"`. Hiding links is interface only: the routes are guarded by `RequireAuth` and
 * `PlayerOnly`, and the backend checks every request.
 */
export function MainNav() {
  const { state } = useAuth()
  if (state.status !== 'authenticated' || state.user.role !== 'PLAYER') return null

  return (
    <nav aria-label="Main">
      {/* Three equal columns on small screens, a compact row from `sm` up; nothing is hidden behind a toggle. */}
      <ul className="grid grid-cols-3 gap-1 sm:flex sm:gap-2">
        {destinations.map(({ to, label, end }) => (
          <li key={to}>
            <NavLink to={to} end={end} className={`block ${link}`}>
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
