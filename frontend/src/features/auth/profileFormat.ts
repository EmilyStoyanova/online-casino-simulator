import { DEFAULT_LOCALE, type Locale } from '../../i18n/locales'
import { i18nFor } from '../../i18n/translate'
import type { UserRole } from '../../types/user'

/** E.g. "2 November 2026" / "2 ноември 2026 г." (UTC); "—" for an unreadable value. */
export function formatDate(isoDate: string, locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).date(isoDate)
}

export function roleLabel(role: UserRole, locale: Locale = DEFAULT_LOCALE): string {
  return i18nFor(locale).t(role === 'ADMIN' ? 'role.ADMIN' : 'role.PLAYER')
}
