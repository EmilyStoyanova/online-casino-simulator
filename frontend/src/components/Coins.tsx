const coinFormat = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 0 })

/**
 * A whole number of virtual coins, e.g. "1,330 coins" (the word "coins", never a currency symbol, so
 * nothing looks like real money). Tabular digits keep amounts aligned. Displays a server value only.
 */
export function Coins({ amount, className = '' }: { amount: number; className?: string }) {
  return (
    <span className={`tabular-nums ${className}`}>
      {coinFormat.format(amount)} {Math.abs(amount) === 1 ? 'coin' : 'coins'}
    </span>
  )
}
