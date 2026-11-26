/**
 * The interface languages and the one browser-storage entry that remembers the choice.
 *
 * Only the locale code (`en` or `bg`) is stored, under {@link LOCALE_STORAGE_KEY} in `localStorage`. It
 * holds nothing about the user, the session, the wallet or a spin. Every access is wrapped in
 * `try`/`catch`: storage can be disabled, full or throw on access, and then the default language is
 * used and the choice lasts for the page visit only.
 */

export const LOCALES = ['en', 'bg'] as const
export type Locale = (typeof LOCALES)[number]

/** English stays the default interface language. */
export const DEFAULT_LOCALE: Locale = 'en'

/** The `localStorage` key of the chosen interface language (the only `localStorage` entry of the SPA). */
export const LOCALE_STORAGE_KEY = 'casino.locale'

/** The BCP 47 tag used for `Intl` number and date formatting. */
export const INTL_LOCALE: Readonly<Record<Locale, string>> = Object.freeze({ en: 'en-GB', bg: 'bg-BG' })

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value)
}

function storage(): Storage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

/** The saved locale, or the default for a missing, unknown or unreadable value. */
export function readStoredLocale(): Locale {
  try {
    const value = storage()?.getItem(LOCALE_STORAGE_KEY)
    return isLocale(value) ? value : DEFAULT_LOCALE
  } catch {
    return DEFAULT_LOCALE
  }
}

/** Saves the locale; a failure is ignored (the choice then lasts for the page visit only). */
export function storeLocale(locale: Locale): void {
  try {
    storage()?.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    // Storage disabled or full: nothing else depends on it.
  }
}
