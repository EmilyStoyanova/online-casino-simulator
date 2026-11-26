import { PageHeader } from '../components/layout/PageHeader'
import { BalanceCard } from '../features/wallet/BalanceCard'
import { TransactionHistory } from '../features/wallet/TransactionHistory'
import { useWalletTransactions } from '../features/wallet/useWalletTransactions'
import { useI18n } from '../i18n/useI18n'

/**
 * `/wallet`, inside `RequireAuth` and `PlayerOnly`: the shared, server-confirmed balance and the
 * player's wallet history (W5-05, §6.2). Read-only; nothing here changes a balance.
 */
export function WalletPage() {
  const history = useWalletTransactions()
  const { t } = useI18n()
  return (
    <div className="space-y-6">
      <PageHeader title={t('wallet.title')}>{t('wallet.intro')}</PageHeader>
      <div className="max-w-md">
        <BalanceCard />
      </div>
      <TransactionHistory state={history.state} onLoadOlder={history.loadOlder} onRefresh={history.refresh} />
    </div>
  )
}
