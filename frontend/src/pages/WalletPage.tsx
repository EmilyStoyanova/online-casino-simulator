import { Link } from 'react-router'
import { secondaryButton } from '../components/buttonStyles'
import { card } from '../components/cardStyles'
import { PageHeader } from '../components/layout/PageHeader'
import { BalanceCard } from '../features/wallet/BalanceCard'

/**
 * `/wallet`, inside `RequireAuth` and `PlayerOnly`. Shows the shared, server-confirmed balance; the
 * transaction history (W5-05) is not loaded yet, which the page says plainly.
 */
export function WalletPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Wallet">Virtual coins only. They have no monetary value and cannot be bought or paid out.</PageHeader>
      <div className="max-w-md">
        <BalanceCard />
      </div>
      <section aria-labelledby="wallet-progress-heading" className={card}>
        <h2 id="wallet-progress-heading" className="text-lg font-semibold text-zinc-100">
          Your transaction history is being built
        </h2>
        <p className="mt-2 text-sm leading-relaxed text-zinc-300">
          Your past wallet entries are not shown here yet. When they are, they will come straight from the
          server.
        </p>
        <div className="mt-5">
          <Link to="/" className={secondaryButton}>
            Back to the lobby
          </Link>
        </div>
      </section>
    </div>
  )
}
