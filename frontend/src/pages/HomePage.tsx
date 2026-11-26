import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { secondaryButton } from '../components/buttonStyles'
import { card } from '../components/cardStyles'
import { PageHeader } from '../components/layout/PageHeader'
import { AccountPanel } from '../features/auth/AccountPanel'
import { useAuth } from '../features/auth/useAuth'
import { SystemStatusCard } from '../features/system-status/SystemStatusCard'
import { houseEdgePercent } from '../features/roulette/oddsRules'
import { useRouletteAvailability } from '../features/roulette/rouletteAvailability'
import { BalanceCard } from '../features/wallet/BalanceCard'
import { useI18n } from '../i18n/useI18n'
import type { UserProfile } from '../types/user'

/** Account panel and platform status, shown on both versions of the home page. */
function AccountAndStatus() {
  return (
    // grid-cols-1 = minmax(0, 1fr): a long email wraps instead of widening the page.
    <div className="grid w-full grid-cols-1 items-start justify-items-center gap-6 md:grid-cols-2">
      <AccountPanel />
      <SystemStatusCard />
    </div>
  )
}

/** The introduction for visitors, administrators and while the session is being checked. */
function Welcome({ administrator }: { administrator: boolean }) {
  const { t } = useI18n()
  return (
    <div className="max-w-2xl space-y-4">
      <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">
        {t('home.welcome.lead')} <span className="text-emerald-400">{t('home.welcome.accent')}</span>
      </h1>
      <p className="text-base text-zinc-400 sm:text-lg">{t('home.welcome.intro')}</p>
      {administrator && <p className="text-sm text-zinc-400">{t('home.welcome.admin')}</p>}
    </div>
  )
}

/** One destination of the lobby, with an honest statement of what it can do today. */
function LobbyCard({
  id,
  title,
  progress,
  to,
  action,
  children,
}: {
  id: string
  title: string
  progress: string
  to: string
  action: string
  children: ReactNode
}) {
  const headingId = `${id}-heading`
  return (
    <section aria-labelledby={headingId} className={`${card} flex flex-col`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold text-zinc-100">
          {title}
        </h2>
        <span className="rounded-full bg-zinc-800 px-2.5 py-0.5 text-xs font-medium text-zinc-300 ring-1 ring-zinc-700">
          {progress}
        </span>
      </div>
      <div className="mt-3 flex-1 space-y-2 text-sm leading-relaxed text-zinc-300">{children}</div>
      <div className="mt-5">
        <Link to={to} className={secondaryButton}>
          {action}
        </Link>
      </div>
    </section>
  )
}

/** The lobby of a signed-in player: the server-confirmed balance and where to go next. */
function PlayerLobby({ user }: { user: UserProfile }) {
  const { t, locale } = useI18n()
  // No availability claim before the backend has shown it; a known absence is said plainly (§7.7).
  const roulette = useRouletteAvailability()
  const unavailable = roulette.status === 'unavailable'
  return (
    <div className="w-full space-y-8">
      <PageHeader title={t('home.lobby.title', { name: user.displayName })}>{t('home.lobby.intro')}</PageHeader>
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        <BalanceCard />
        <LobbyCard
          id="lobby-roulette"
          title={t('home.roulette.title')}
          progress={unavailable ? t('home.roulette.badgeUnavailable') : t('home.roulette.badge')}
          to="/roulette"
          action={t('home.roulette.action')}
        >
          <p>{t('home.roulette.facts', { percent: houseEdgePercent(locale) })}</p>
          <p className="text-zinc-400">{unavailable ? t('home.roulette.unavailable') : t('home.roulette.serverDraws')}</p>
        </LobbyCard>
        <LobbyCard
          id="lobby-wallet"
          title={t('home.wallet.title')}
          progress={t('home.wallet.badge')}
          to="/wallet"
          action={t('home.wallet.action')}
        >
          <p>{t('home.wallet.text')}</p>
          <p className="text-zinc-400">{t('home.wallet.history')}</p>
        </LobbyCard>
      </div>
    </div>
  )
}

/**
 * `/`: the player lobby for a signed-in player, otherwise the welcome introduction. The account panel
 * and the platform status keep their place in the tree in both versions, so the switch from
 * "checking" to the lobby neither remounts them nor asks for the platform status again.
 */
export function HomePage() {
  const { state } = useAuth()
  const player = state.status === 'authenticated' && state.user.role === 'PLAYER' ? state.user : null

  return (
    <div className={player ? 'flex flex-col gap-8' : 'flex flex-col items-center gap-10 text-center'}>
      {player ? <PlayerLobby user={player} /> : <Welcome administrator={state.status === 'authenticated'} />}
      <div className={player ? 'w-full' : 'w-full max-w-4xl'}>
        <AccountAndStatus />
      </div>
    </div>
  )
}
