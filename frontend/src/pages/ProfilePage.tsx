import { card } from '../components/cardStyles'
import { DetailRow } from '../components/DetailRow'
import { formatDate, roleLabel } from '../features/auth/profileFormat'
import { useAuth } from '../features/auth/useAuth'
import { interestName } from '../features/profile/interestText'
import { ProfileForm } from '../features/profile/ProfileForm'
import { useInterests, type InterestCatalog } from '../features/profile/useInterests'
import type { Locale } from '../i18n/locales'
import { useI18n } from '../i18n/useI18n'
import type { UserProfile } from '../types/user'

function interestNames(codes: readonly string[], catalog: InterestCatalog, locale: Locale): string[] {
  if (catalog.status !== 'loaded') return [...codes]
  const names = new Map(catalog.interests.map((interest) => [interest.code, interestName(interest, locale)]))
  return codes.map((code) => names.get(code) ?? code)
}

/** The saved profile, as the server returned it last. */
function AccountDetails({ user, catalog }: { user: UserProfile; catalog: InterestCatalog }) {
  const { t, locale } = useI18n()
  const names = interestNames(user.interests, catalog, locale)

  return (
    <section aria-labelledby="profile-details-heading" className={card}>
      <h2 id="profile-details-heading" className="mb-4 text-lg font-semibold text-zinc-100">
        {t('profile.details')}
      </h2>
      <dl className="divide-y divide-zinc-800">
        <DetailRow label={t('common.displayName')}>{user.displayName}</DetailRow>
        <DetailRow label={t('common.email')}>{user.email}</DetailRow>
        <DetailRow label={t('common.role')}>{roleLabel(user.role, locale)}</DetailRow>
        <DetailRow label={t('common.memberSince')}>{formatDate(user.createdAt, locale)}</DetailRow>
        <DetailRow label={t('common.adultConfirmed')}>{formatDate(user.adultConfirmedAt, locale)}</DetailRow>
      </dl>
      <h3 className="mt-5 text-sm font-medium text-zinc-300">{t('profile.interests')}</h3>
      {names.length === 0 ? (
        <p className="mt-2 text-sm text-zinc-400">{t('profile.noInterests')}</p>
      ) : (
        <ul aria-label={t('profile.savedInterests')} className="mt-2 flex flex-wrap gap-2">
          {names.map((name) => (
            <li
              key={name}
              className="rounded-full bg-emerald-500/10 px-3 py-1 text-xs font-medium text-emerald-200 ring-1 ring-emerald-500/30"
            >
              {name}
            </li>
          ))}
        </ul>
      )}
      <p className="mt-5 text-xs leading-relaxed text-zinc-400">{t('profile.readOnly')}</p>
    </section>
  )
}

/** `/profile`, inside `RequireAuth`: the signed-in user's profile and the form to edit it. */
export function ProfilePage() {
  const { state } = useAuth()
  const { t } = useI18n()
  const { catalog, reload } = useInterests()

  // RequireAuth renders this page only for a signed-in user.
  if (state.status !== 'authenticated') return null
  const { user } = state

  return (
    <div className="mx-auto w-full max-w-5xl space-y-6">
      <div className="space-y-2">
        <h1 className="text-3xl font-bold tracking-tight">{t('profile.title')}</h1>
        <p className="text-sm text-zinc-400">{t('profile.intro')}</p>
      </div>
      {/* grid-cols-1 = minmax(0, 1fr): a long email wraps instead of widening the page. */}
      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <AccountDetails user={user} catalog={catalog} />
        <section aria-labelledby="profile-edit-heading" className={card}>
          <h2 id="profile-edit-heading" className="mb-4 text-lg font-semibold text-zinc-100">
            {t('common.editProfile')}
          </h2>
          <ProfileForm key={user.id} user={user} catalog={catalog} onRetryCatalog={reload} />
        </section>
      </div>
    </div>
  )
}
