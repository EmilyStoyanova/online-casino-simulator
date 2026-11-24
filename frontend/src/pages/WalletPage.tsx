import { Link } from 'react-router'
import { secondaryButton } from '../components/buttonStyles'
import { card } from '../components/cardStyles'
import { PageHeader } from '../components/layout/PageHeader'

/**
 * `/wallet`, inside `RequireAuth` and `PlayerOnly`. Placeholder until the balance (W5-04) and the
 * transaction history (W5-05) are loaded from the server: it shows no number and sends no request.
 */
export function WalletPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Wallet">Virtual coins only. They have no monetary value and cannot be bought or paid out.</PageHeader>
      <section aria-labelledby="wallet-progress-heading" className={card}>
        <h2 id="wallet-progress-heading" className="text-lg font-semibold text-zinc-100">
          Your wallet page is being built
        </h2>
        <p className="mt-2 text-sm leading-relaxed text-zinc-300">
          Your balance and transaction history are not shown here yet. When they are, they will come straight
          from the server.
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
