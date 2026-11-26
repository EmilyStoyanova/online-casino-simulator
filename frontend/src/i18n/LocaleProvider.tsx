import { useCallback, useLayoutEffect, useMemo, useState, type ReactNode } from 'react'
import { LocaleContext, type LocaleContextValue } from './localeContext'
import { readStoredLocale, storeLocale, type Locale } from './locales'
import { i18nFor } from './translate'

/**
 * Holds the interface language for the whole app (render it outermost, around the other providers).
 *
 * - Starts with the saved choice (`casino.locale` in `localStorage`), or English when there is none,
 *   it is not a known language, or storage cannot be read.
 * - `setLocale` changes only the language: no provider below is re-created and no component is
 *   remounted, so the session, the wallet, the bet slip and an unresolved spin stay as they are. Texts
 *   kept in state are messages (`Message`) and are translated again on the next render.
 * - Keeps `<html lang>`, the document title and the meta description in the current language, so
 *   screen readers pronounce the page correctly.
 */
export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(readStoredLocale)

  const setLocale = useCallback((next: Locale) => {
    setLocaleState(next)
    storeLocale(next)
  }, [])

  const value = useMemo<LocaleContextValue>(() => ({ ...i18nFor(locale), setLocale }), [locale, setLocale])

  // Before paint, so the page is never shown with the previous language's `lang`.
  useLayoutEffect(() => {
    const { t } = value
    document.documentElement.lang = locale
    document.title = t('app.name')
    document.querySelector('meta[name="description"]')?.setAttribute('content', t('app.metaDescription'))
  }, [locale, value])

  return <LocaleContext value={value}>{children}</LocaleContext>
}
