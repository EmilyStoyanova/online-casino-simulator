import { DEFAULT_LOCALE, type Locale } from '../../i18n/locales'
import { i18nFor, type MessageKey } from '../../i18n/translate'
import type { WalletTransactionType } from '../../types/wallet'

/**
 * Display rules of the wallet history (§6.2). Pure functions: no React, no I/O. Every value shown
 * comes from the server; nothing here computes a balance. `locale` defaults to English.
 */

const TYPE_LABELS: Record<WalletTransactionType, MessageKey> = {
  STARTING_BALANCE: 'txType.STARTING_BALANCE',
  ROULETTE_STAKE: 'txType.ROULETTE_STAKE',
  ROULETTE_PAYOUT: 'txType.ROULETTE_PAYOUT',
}

/** Label of an entry type; a type added by a later contract version is a generic "Other entry". */
export function transactionTypeLabel(type: string, locale: Locale = DEFAULT_LOCALE): string {
  const key = Object.prototype.hasOwnProperty.call(TYPE_LABELS, type) ? TYPE_LABELS[type as WalletTransactionType] : 'txType.other'
  return i18nFor(locale).t(key)
}

/**
 * A ledger amount with an explicit sign and the word "coins", never a currency: "+360 coins",
 * "−30 coins" (U+2212 minus sign), "+1 coin". The sign is text, so it never depends on colour.
 */
export function formatSignedCoins(amount: number, locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).signedCoins(amount)
}

/**
 * An entry time in the browser's local time zone (medium date and short time), e.g.
 * "20 Nov 2026, 12:15" (`en-GB`) or "20.11.2026 г., 12:15 ч." (`bg-BG`). `timeZone` exists for tests
 * only; the app always uses the local zone. An unreadable value shows as "—" instead of a wrong date.
 */
export function formatEntryTime(isoDate: string, timeZone?: string, locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).dateTime(isoDate, timeZone)
}
