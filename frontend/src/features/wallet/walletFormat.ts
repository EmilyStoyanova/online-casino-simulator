import type { WalletTransactionType } from '../../types/wallet'

/**
 * Display rules of the wallet history (§6.2). Pure functions: no React, no I/O. Every value shown
 * comes from the server; nothing here computes a balance.
 */

const TYPE_LABELS: Record<WalletTransactionType, string> = {
  STARTING_BALANCE: 'Starting balance',
  ROULETTE_STAKE: 'Roulette stake',
  ROULETTE_PAYOUT: 'Roulette payout',
}

/** Label of an entry type; a type added by a later contract version is a generic "Other entry". */
export function transactionTypeLabel(type: string): string {
  return Object.prototype.hasOwnProperty.call(TYPE_LABELS, type)
    ? TYPE_LABELS[type as WalletTransactionType]
    : 'Other entry'
}

const coinFormat = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 0 })

/**
 * A ledger amount with an explicit sign and the word "coins", never a currency: "+360 coins",
 * "−30 coins" (U+2212 minus sign), "+1 coin". The sign is text, so it never depends on colour.
 */
export function formatSignedCoins(amount: number): string {
  const sign = amount > 0 ? '+' : amount < 0 ? '−' : ''
  return `${sign}${coinFormat.format(Math.abs(amount))} ${Math.abs(amount) === 1 ? 'coin' : 'coins'}`
}

/**
 * An entry time in the browser's local time zone (`en-GB`, medium date and short time), e.g.
 * "20 Nov 2026, 12:15". `timeZone` exists for tests only; the app always uses the local zone. An
 * unreadable value shows as "—" instead of a wrong date.
 */
export function formatEntryTime(isoDate: string, timeZone?: string): string {
  const date = new Date(isoDate)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(date)
}
