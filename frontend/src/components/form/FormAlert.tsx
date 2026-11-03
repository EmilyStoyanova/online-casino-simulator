import type { ReactNode } from 'react'

const tones = {
  error: 'border-rose-500/40 bg-rose-500/10 text-rose-200',
  info: 'border-sky-500/40 bg-sky-500/10 text-sky-200',
  success: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200',
} as const

/** A message above a form. Errors are announced immediately (`role="alert"`), others politely. */
export function FormAlert({ tone, children }: { tone: keyof typeof tones; children: ReactNode }) {
  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      className={`rounded-lg border px-3.5 py-3 text-sm leading-relaxed ${tones[tone]}`}
    >
      {children}
    </div>
  )
}
