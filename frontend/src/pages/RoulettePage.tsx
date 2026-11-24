import { Link } from 'react-router'
import { secondaryButton } from '../components/buttonStyles'
import { card } from '../components/cardStyles'
import { PageHeader } from '../components/layout/PageHeader'
import { BalanceCard } from '../features/wallet/BalanceCard'

/**
 * `/roulette`, inside `RequireAuth` and `PlayerOnly`. Shows the shared, server-confirmed balance; the
 * betting table (W5-06) and the spin flow (W5-07) do not exist yet, which the page says plainly. It
 * sends no request of its own.
 */
export function RoulettePage() {
  return (
    <div className="space-y-6">
      <PageHeader title="European roulette">
        Single-zero roulette with virtual coins only. Every number is drawn by the server, never in your browser.
      </PageHeader>
      <div className="max-w-md">
        <BalanceCard />
      </div>
      <section aria-labelledby="roulette-progress-heading" className={card}>
        <h2 id="roulette-progress-heading" className="text-lg font-semibold text-zinc-100">
          The roulette table is being built
        </h2>
        <p className="mt-2 text-sm leading-relaxed text-zinc-300">
          You cannot place bets yet, and nothing on this page uses your coins.
        </p>
        <p className="mt-4 text-sm text-zinc-400">Planned for this table:</p>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm leading-relaxed text-zinc-300">
          <li>Virtual chips and the European table with its five bet types.</li>
          <li>A review step before every spin.</li>
          <li>Results and balances confirmed by the server.</li>
          <li>The odds and rules of every bet, including the house edge.</li>
        </ul>
        <div className="mt-5">
          <Link to="/" className={secondaryButton}>
            Back to the lobby
          </Link>
        </div>
      </section>
    </div>
  )
}
