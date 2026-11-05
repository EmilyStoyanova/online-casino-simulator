import type { UserRole } from '../../types/user'

const dateFormat = new Intl.DateTimeFormat('en-GB', { dateStyle: 'long', timeZone: 'UTC' })

/** E.g. "2 November 2026"; "—" for an unreadable value. */
export function formatDate(isoDate: string): string {
  const date = new Date(isoDate)
  return Number.isNaN(date.getTime()) ? '—' : dateFormat.format(date)
}

export function roleLabel(role: UserRole): string {
  return role === 'ADMIN' ? 'Administrator' : 'Player'
}
