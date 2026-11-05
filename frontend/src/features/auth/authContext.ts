import { createContext } from 'react'
import type { LoginRequest, RegisterRequest } from '../../types/auth'
import type { UserProfile } from '../../types/user'

/**
 * What the SPA knows about the session. Kept in memory only, never in browser storage; the session
 * itself is the `HttpOnly` cookie, which JavaScript cannot read.
 *
 * - `checking`: after a page load, while `GET /api/v1/users/me` asks the server.
 * - `authenticated`: the server confirmed the session (start-up check, registration or login).
 * - `anonymous`: the server confirmed that there is no valid session (`401`), or the user signed out.
 *   `session-expired` when the session had ended (`401 SESSION_EXPIRED`).
 * - `unknown`: the start-up check failed (no connection, server error). An earlier session may still
 *   be active, so the SPA claims neither state and offers to check again.
 *
 * `RequireAuth` shows protected pages only for `authenticated` and redirects to the login page only
 * for `anonymous`.
 */
export type AuthState =
  | { status: 'checking' }
  | { status: 'authenticated'; user: UserProfile }
  | { status: 'anonymous'; reason: 'signed-out' | 'session-expired' }
  | { status: 'unknown' }

export interface AuthContextValue {
  state: AuthState
  register: (request: RegisterRequest) => Promise<UserProfile>
  login: (request: LoginRequest) => Promise<UserProfile>
  /** Ends the session and goes to the home page; on failure the state is unchanged. */
  logout: () => Promise<void>
  /** Asks the server again who is signed in (after `unknown`). */
  checkSession: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
