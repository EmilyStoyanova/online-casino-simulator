import { Link, Outlet } from 'react-router'
import { secondaryButton } from '../../components/buttonStyles'
import { card } from '../../components/cardStyles'
import { useI18n } from '../../i18n/useI18n'
import { useAuth } from './useAuth'

/**
 * Layout route for pages that only players can use (the roulette and the wallet), nested inside
 * `RequireAuth`:
 *
 * ```tsx
 * <Route element={<RequireAuth />}>
 *   <Route element={<PlayerOnly />}>…</Route>
 * </Route>
 * ```
 *
 * `RequireAuth` already handles visitors (login with `returnTo`) and the `checking`/`unknown` states,
 * so only a confirmed session reaches this guard. Players see the page; administrators, who have no
 * wallet, see an explanation instead and the page sends no request. It uses the role the server
 * returned with the profile and makes no request of its own.
 *
 * Interface only: Spring Security answers `403 ACCESS_DENIED` to an administrator on every
 * `/api/v1/wallet/**` and `/api/v1/games/**` request, and stays the security boundary.
 */
export function PlayerOnly() {
  const { state } = useAuth()
  const { t } = useI18n()

  // Defensive: outside RequireAuth an unconfirmed session must never show the page either.
  if (state.status !== 'authenticated') return null
  if (state.user.role === 'PLAYER') return <Outlet />

  return (
    <div className="flex flex-1 items-start justify-center sm:items-center">
      <section aria-labelledby="player-only-heading" className={`${card} max-w-md sm:p-8`}>
        <h1 id="player-only-heading" className="mb-3 text-xl font-semibold tracking-tight text-zinc-50">
          {t('playerOnly.title')}
        </h1>
        <p className="text-sm leading-relaxed text-zinc-300">{t('playerOnly.text')}</p>
        <div className="mt-5">
          <Link to="/" className={secondaryButton}>
            {t('common.backHome')}
          </Link>
        </div>
      </section>
    </div>
  )
}
