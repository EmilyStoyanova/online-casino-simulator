import { createContext } from 'react'
import type { LoginRequest, RegisterRequest } from '../../types/auth'
import type { UpdateProfileRequest, UserProfile } from '../../types/user'

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
  /**
   * Saves profile changes (`PATCH /api/v1/users/me`) and shows the saved profile everywhere (header,
   * account panel, profile page). Rejects with the `ApiError`; the state is then unchanged, except
   * that a `401` ends the signed-in state.
   */
  updateProfile: (request: UpdateProfileRequest) => Promise<UserProfile>
  /**
   * Loads the saved profile again (`GET /api/v1/users/me`) without passing through `checking`, so a
   * page that shows it stays mounted (e.g. after `409 CONCURRENT_MODIFICATION`). Failures as above.
   */
  reloadProfile: () => Promise<UserProfile>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
