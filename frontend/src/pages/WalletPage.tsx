import { PageHeader } from '../components/layout/PageHeader'
import { BalanceCard } from '../features/wallet/BalanceCard'
import { TransactionHistory } from '../features/wallet/TransactionHistory'
import { useWalletTransactions } from '../features/wallet/useWalletTransactions'

/**
 * `/wallet`, inside `RequireAuth` and `PlayerOnly`: the shared, server-confirmed balance and the
 * player's wallet history (W5-05, §6.2). Read-only; nothing here changes a balance.
 */
export function WalletPage() {
  const history = useWalletTransactions()
  return (
    <div className="space-y-6">
      <PageHeader title="Wallet">Virtual coins only. They have no monetary value and cannot be bought or paid out.</PageHeader>
      <div className="max-w-md">
        <BalanceCard />
      </div>
      <TransactionHistory state={history.state} onLoadOlder={history.loadOlder} onRefresh={history.refresh} />
    </div>
  )
}
