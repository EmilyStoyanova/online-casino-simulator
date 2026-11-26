import { useContext } from 'react'
import { LocaleContext, type LocaleContextValue } from './localeContext'

/** The current language: `t`, `rich`, the formatters, `locale` and `setLocale` (see `LocaleProvider`). */
export function useI18n(): LocaleContextValue {
  return useContext(LocaleContext)
}
