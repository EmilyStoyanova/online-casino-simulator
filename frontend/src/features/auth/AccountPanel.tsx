import { Link } from 'react-router'
import { DetailRow as Row } from '../../components/DetailRow'
import { primaryButton, secondaryButton } from '../../components/buttonStyles'
import { useI18n } from '../../i18n/useI18n'
import type { UserProfile } from '../../types/user'
import { formatDate, roleLabel } from './profileFormat'
import { CheckingSession, SessionUnavailable } from './SessionStatus'
import { useAuth } from './useAuth'
import { useSignOut } from './useSignOut'

function SignedIn({ user }: { user: UserProfile }) {
  const { signOut, pending, error } = useSignOut()
  const { t, rich, locale } = useI18n()

  return (
    <>
      <p className="mb-3 text-sm text-zinc-300">
        {rich('account.signedInAs', { name: <span className="font-semibold text-emerald-300">{user.displayName}</span> })}
      </p>
      <dl className="divide-y divide-zinc-800">
        <Row label={t('common.email')}>{user.email}</Row>
        <Row label={t('common.role')}>{roleLabel(user.role, locale)}</Row>
        <Row label={t('common.memberSince')}>{formatDate(user.createdAt, locale)}</Row>
        <Row label={t('common.adultConfirmed')}>{formatDate(user.adultConfirmedAt, locale)}</Row>
      </dl>
      <p className="mt-4 text-xs leading-relaxed text-zinc-400">{t('account.sessionInfo')}</p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Link to="/profile" className={primaryButton}>
          {t('common.editProfile')}
        </Link>
        <button type="button" onClick={() => void signOut()} disabled={pending} className={secondaryButton}>
          {pending ? t('common.signingOut') : t('common.signOut')}
        </button>
        {error && (
          <p role="alert" className="text-sm text-rose-300">
            {t(error)}
          </p>
        )}
      </div>
    </>
  )
}

function SignedOut({ expired }: { expired: boolean }) {
  const { t } = useI18n()
  return (
    <>
      <p className="text-sm leading-relaxed text-zinc-300">
        {expired ? t('session.ended') : t('account.signedOut')} {t('account.joinPrompt')}
      </p>
      <div className="mt-4 flex flex-wrap gap-3">
        <Link to="/register" className={primaryButton}>
          {t('common.createAccount')}
        </Link>
        <Link to="/login" className={secondaryButton}>
          {t('common.signIn')}
        </Link>
      </div>
    </>
  )
}

/** The user's account on the home page: profile data when signed in, otherwise how to sign in. */
export function AccountPanel() {
  const { state } = useAuth()
  const { t } = useI18n()

  return (
    <section
      aria-labelledby="account-heading"
      className="w-full rounded-2xl border border-zinc-800 bg-zinc-900/70 p-6 text-left shadow-xl"
    >
      <h2 id="account-heading" className="mb-4 text-lg font-semibold text-zinc-100">
        {t('account.heading')}
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
