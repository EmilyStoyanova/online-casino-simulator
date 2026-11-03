import type { ReactNode } from 'react'

/** Centred card for the sign-in and registration pages. */
export function AuthCard({
  title,
  description,
  children,
  footer,
}: {
  title: string
  description: ReactNode
  children: ReactNode
  footer: ReactNode
}) {
  return (
    <div className="flex flex-1 items-start justify-center sm:items-center">
      <section
        aria-labelledby="auth-heading"
        className="w-full max-w-md rounded-2xl border border-zinc-800 bg-zinc-900/70 p-6 shadow-xl shadow-black/30 sm:p-8"
      >
        <div className="mb-6 space-y-2">
          <h1 id="auth-heading" className="text-2xl font-bold tracking-tight text-zinc-50">
            {title}
          </h1>
          <div className="text-sm leading-relaxed text-zinc-400">{description}</div>
        </div>
        {children}
        <div className="mt-6 border-t border-zinc-800 pt-5 text-center text-sm text-zinc-400">{footer}</div>
      </section>
    </div>
  )
}
