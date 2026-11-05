import { DetailRow } from '../components/DetailRow'
import { formatDate, roleLabel } from '../features/auth/profileFormat'
import { useAuth } from '../features/auth/useAuth'
import { ProfileForm } from '../features/profile/ProfileForm'
import { useInterests, type InterestCatalog } from '../features/profile/useInterests'
import type { UserProfile } from '../types/user'

const card = 'w-full rounded-2xl border border-zinc-800 bg-zinc-900/70 p-6 shadow-xl shadow-black/30'

function interestNames(codes: readonly string[], catalog: InterestCatalog): string[] {
  if (catalog.status !== 'loaded') return [...codes]
  const names = new Map(catalog.interests.map((interest) => [interest.code, interest.name]))
  return codes.map((code) => names.get(code) ?? code)
}

/** The saved profile, as the server returned it last. */
function AccountDetails({ user, catalog }: { user: UserProfile; catalog: InterestCatalog }) {
  const names = interestNames(user.interests, catalog)

  return (
    <section aria-labelledby="profile-details-heading" className={card}>
      <h2 id="profile-details-heading" className="mb-4 text-lg font-semibold text-zinc-100">
        Account details
      </h2>
      <dl className="divide-y divide-zinc-800">
        <DetailRow label="Display name">{user.displayName}</DetailRow>
        <DetailRow label="Email">{user.email}</DetailRow>
        <DetailRow label="Role">{roleLabel(user.role)}</DetailRow>
        <DetailRow label="Member since">{formatDate(user.createdAt)}</DetailRow>
        <DetailRow label="18+ confirmed">{formatDate(user.adultConfirmedAt)}</DetailRow>
      </dl>
      <h3 className="mt-5 text-sm font-medium text-zinc-300">Interests</h3>
      {names.length === 0 ? (
        <p className="mt-2 text-sm text-zinc-500">None selected yet.</p>
      ) : (
        <ul aria-label="Saved interests" className="mt-2 flex flex-wrap gap-2">
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
      <p className="mt-5 text-xs leading-relaxed text-zinc-500">
        Your email, role and 18+ confirmation cannot be changed here.
      </p>
    </section>
  )
}

/** `/profile`, inside `RequireAuth`: the signed-in user's profile and the form to edit it. */
export function ProfilePage() {
  const { state } = useAuth()
  const { catalog, reload } = useInterests()

  // RequireAuth renders this page only for a signed-in user.
  if (state.status !== 'authenticated') return null
  const { user } = state

  return (
    <div className="mx-auto w-full max-w-5xl space-y-6">
      <div className="space-y-2">
        <h1 className="text-3xl font-bold tracking-tight">My profile</h1>
        <p className="text-sm text-zinc-400">Choose how you appear to other players and what you want to learn.</p>
      </div>
      {/* grid-cols-1 = minmax(0, 1fr): a long email is truncated instead of widening the page. */}
      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <AccountDetails user={user} catalog={catalog} />
        <section aria-labelledby="profile-edit-heading" className={card}>
          <h2 id="profile-edit-heading" className="mb-4 text-lg font-semibold text-zinc-100">
            Edit profile
          </h2>
          <ProfileForm key={user.id} user={user} catalog={catalog} onRetryCatalog={reload} />
        </section>
      </div>
    </div>
  )
}
