import type { MouseEvent, ReactNode } from 'react'
import { Link } from 'react-router'
import { UserMenu } from '../../features/auth/UserMenu'
import { BalanceBadge } from '../../features/wallet/BalanceBadge'
import { MainNav } from './MainNav'

const MAIN_ID = 'main-content'

/** Moves the focus to the page content without changing the URL (the router owns the location). */
function skipToMain(event: MouseEvent<HTMLAnchorElement>) {
  event.preventDefault()
  document.getElementById(MAIN_ID)?.focus()
}

export function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col bg-zinc-950 text-zinc-100">
      <a
        href={`#${MAIN_ID}`}
        onClick={skipToMain}
        className="sr-only rounded-md bg-emerald-500 px-4 py-2 text-sm font-semibold text-zinc-950 focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50"
      >
        Skip to main content
      </a>
      <header className="border-b border-zinc-800">
        <div className="mx-auto max-w-6xl space-y-3 px-4 py-4">
          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <Link
                to="/"
                className="rounded-md text-lg font-bold tracking-tight focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-emerald-400"
              >
                <span className="text-emerald-400" aria-hidden="true">
                  ◆
                </span>{' '}
                Online Casino Simulator
              </Link>
              <span className="rounded-full bg-amber-400/10 px-3 py-1 text-xs font-medium text-amber-300 ring-1 ring-amber-400/30">
                Virtual currency only
              </span>
            </div>
            {/* The player's server-confirmed balance (nothing for visitors and administrators), then the account menu. */}
            <div className="flex flex-wrap items-center justify-end gap-3">
              <BalanceBadge />
              <nav aria-label="Account">
                <UserMenu />
              </nav>
            </div>
          </div>
          <MainNav />
        </div>
      </header>

      {/* tabIndex -1: the skip link can focus it; it is not a tab stop itself. */}
      <main
        id={MAIN_ID}
        tabIndex={-1}
        className="mx-auto flex w-full max-w-6xl flex-1 flex-col px-4 py-10 focus:outline-none"
      >
        {children}
      </main>

      <footer className="border-t border-zinc-800">
        <p className="mx-auto max-w-6xl px-4 py-4 text-xs leading-relaxed text-zinc-400">
          This is a simulator and educational platform. It uses virtual coins with no monetary value — no
          real-money deposits, withdrawals, purchases or prizes. It is not a treatment for gambling
          problems; if gambling is causing you harm, please contact a professional support service.
        </p>
      </footer>
    </div>
  )
}
