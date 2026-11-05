import type { ReactNode } from 'react'

/** One label/value pair of a `<dl>`. */
export function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2">
      <dt className="text-sm text-zinc-400">{label}</dt>
      <dd className="min-w-0 truncate text-sm font-medium text-zinc-100">{children}</dd>
    </div>
  )
}
