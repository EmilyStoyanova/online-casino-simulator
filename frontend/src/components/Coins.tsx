import { useI18n } from '../i18n/useI18n'

/**
 * A whole number of virtual coins, e.g. "1,330 coins" / "1 330 монети" (the word, never a currency
 * symbol, so nothing looks like real money). Tabular digits keep amounts aligned. Displays a server
 * value only.
 */
export function Coins({ amount, className = '' }: { amount: number; className?: string }) {
  const { coins } = useI18n()
  return <span className={`tabular-nums ${className}`}>{coins(amount)}</span>
}
