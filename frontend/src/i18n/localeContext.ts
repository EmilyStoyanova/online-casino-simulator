import { createContext } from 'react'
import { DEFAULT_LOCALE, type Locale } from './locales'
import { i18nFor, type I18n } from './translate'

export interface LocaleContextValue extends I18n {
  /** Switches the interface language and remembers it; no other state changes. */
  readonly setLocale: (locale: Locale) => void
}

/**
 * Outside a `LocaleProvider` (tests of single components) the interface is English and cannot be
 * switched. The app renders the provider in `App.tsx`.
 */
export const LocaleContext = createContext<LocaleContextValue>({
  ...i18nFor(DEFAULT_LOCALE),
  setLocale: () => undefined,
})
