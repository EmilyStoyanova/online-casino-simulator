import type { ReactNode } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { loginPath } from './returnPath'
import { CheckingSession, SessionUnavailable } from './SessionStatus'
import { useAuth } from './useAuth'

function StatusCard({ title, busy, children }: { title: string; busy?: boolean; children: ReactNode }) {
  return (
    <div className="flex flex-1 items-start justify-center sm:items-center">
      <section
        aria-labelledby="require-auth-heading"
        className="w-full max-w-md rounded-2xl border border-zinc-800 bg-zinc-900/70 p-6 shadow-xl shadow-black/30 sm:p-8"
      >
        <h1 id="require-auth-heading" className="mb-4 text-xl font-semibold tracking-tight text-zinc-50">
          {title}
        </h1>
        <div aria-live="polite" aria-busy={busy}>
          {children}
        </div>
      </section>
    </div>
  )
}

/**
 * Layout route for pages that need a signed-in user (AC-11.2): `<Route element={<RequireAuth />}>`.
 *
 * - `authenticated`: renders the page (`<Outlet />`).
 * - `anonymous`: replaces the location with the login page, which comes back here afterwards
 *   (`?returnTo=`, internal paths only). After `SESSION_EXPIRED` the login page explains why.
 * - `checking`: neither the page nor a redirect until the server has answered, so a reload with a
 *   valid session never passes through the login page.
 * - `unknown`: the server could not be asked; the user is not treated as signed out, and the page is
 *   not shown either.
 *
 * Only a convenience for the interface: the server checks the session on every request, so it stays
 * the security boundary.
 */
export function RequireAuth() {
  const { state } = useAuth()
  const location = useLocation()
  const here = `${location.pathname}${location.search}${location.hash}`

  switch (state.status) {
    case 'authenticated':
      return <Outlet />
    case 'anonymous':
      return <Navigate to={loginPath(here)} replace />
    case 'checking':
      return (
        <StatusCard title="One moment" busy>
          <CheckingSession />
        </StatusCard>
      )
    case 'unknown':
      return (
        <StatusCard title="Sign-in status unavailable">
          <SessionUnavailable returnTo={here} />
        </StatusCard>
      )
  }
}
