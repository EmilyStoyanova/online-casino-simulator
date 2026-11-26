import { Link } from 'react-router'
import { ghostButton, primaryButton, secondaryButton } from '../../components/buttonStyles'
import { useI18n } from '../../i18n/useI18n'
import { loginPath } from './returnPath'
import { useAuth } from './useAuth'
import { useSignOut } from './useSignOut'

/** While `GET /api/v1/users/me` is pending (`checking`). */
export function CheckingSession() {
  const { t } = useI18n()
  return (
    <p className="flex items-center gap-2 text-sm text-zinc-400">
      <span aria-hidden="true" className="size-2 animate-pulse rounded-full bg-emerald-400/70" />
      {t('session.checking')}
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
  const { t } = useI18n()

  return (
    <>
      <p className="text-sm leading-relaxed text-zinc-300">{t('session.unavailable')}</p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button type="button" onClick={checkSession} className={primaryButton}>
          {t('common.tryAgain')}
        </button>
        <Link to={loginPath(returnTo)} className={secondaryButton}>
          {t('common.signIn')}
        </Link>
        <button type="button" onClick={() => void signOut()} disabled={pending} className={ghostButton}>
          {pending ? t('common.signingOut') : t('common.signOut')}
        </button>
      </div>
      {error && (
        <p role="alert" className="mt-3 text-sm text-rose-300">
          {t(error)}
        </p>
      )}
    </>
  )
}
