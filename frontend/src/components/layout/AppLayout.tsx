import type { ReactNode } from 'react'

export function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col bg-zinc-950 text-zinc-100">
      <header className="border-b border-zinc-800">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-4">
          <span className="text-lg font-bold tracking-tight">
            <span className="text-emerald-400">◆</span> Online Casino Simulator
          </span>
          <span className="rounded-full bg-amber-400/10 px-3 py-1 text-xs font-medium text-amber-300 ring-1 ring-amber-400/30">
            Virtual currency only
          </span>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col px-4 py-10">{children}</main>

      <footer className="border-t border-zinc-800">
        <p className="mx-auto max-w-5xl px-4 py-4 text-xs leading-relaxed text-zinc-500">
          This is a simulator and educational platform. It uses virtual coins with no monetary value — no
          real-money deposits, withdrawals, purchases or prizes. It is not a treatment for gambling
          problems; if gambling is causing you harm, please contact a professional support service.
        </p>
      </footer>
    </div>
  )
}
