import type { ReactNode } from 'react'

/** The page title (the page's only `h1`) and a short description below it. */
export function PageHeader({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return (
    <div className="space-y-2">
      <h1 className="text-3xl font-bold tracking-tight">{title}</h1>
      {children && <p className="max-w-3xl text-sm leading-relaxed text-zinc-400">{children}</p>}
    </div>
  )
}
