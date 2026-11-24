import { Link } from 'react-router'
import { secondaryButton } from '../components/buttonStyles'
import { card } from '../components/cardStyles'
import { PageHeader } from '../components/layout/PageHeader'
import { RouletteWheel } from '../features/roulette/RouletteWheel'
import { BalanceCard } from '../features/wallet/BalanceCard'

/**
 * `/roulette`, inside `RequireAuth` and `PlayerOnly`. Shows the wheel (W5-P01, standing still), the
 * shared server-confirmed balance and an honest progress note: the betting table (W5-06) and the spin
 * flow (W5-07) do not exist yet. It sends no API request of its own.
 */
export function RoulettePage() {
  return (
    <div className="space-y-6">
      <PageHeader title="European roulette">
        Single-zero roulette with virtual coins only. Every number is drawn by the server, never in your browser.
      </PageHeader>
      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-2">
        <section aria-labelledby="roulette-wheel-heading" className={card}>
          <h2 id="roulette-wheel-heading" className="mb-4 text-lg font-semibold text-zinc-100">
            The wheel
          </h2>
          <RouletteWheel />
          <p className="mt-4 text-center text-xs text-zinc-500">
            The wheel stands still. It will turn only to show a number drawn by the server.
          </p>
        </section>
        <div className="space-y-6">
          <BalanceCard />
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
              <li>Results and balances confirmed by the server, shown on the wheel.</li>
              <li>The odds and rules of every bet, including the house edge.</li>
            </ul>
            <div className="mt-5">
              <Link to="/" className={secondaryButton}>
                Back to the lobby
              </Link>
            </div>
          </section>
        </div>
      </div>
    </div>
  )
}
