import { createContext } from 'react'
import type { LoginRequest, RegisterRequest } from '../../types/auth'
import type { UserProfile } from '../../types/user'

/**
 * What the SPA knows about the session. Kept in memory only, never in browser storage.
 *
 * - `unknown`: after a page load. The session cookie is `HttpOnly`, and the endpoint that reports the
 *   signed-in user (`GET /api/v1/users/me`, W2-10) does not exist yet, so an earlier session may or
 *   may not still be active.
 * - `authenticated`: from the `UserProfile` of a successful registration or login in this page.
 * - `anonymous`: after logout, or after the server reported `401 SESSION_EXPIRED`.
 */
export type AuthState =
  | { status: 'unknown' }
  | { status: 'authenticated'; user: UserProfile }
  | { status: 'anonymous'; reason: 'signed-out' | 'session-expired' }

export interface AuthContextValue {
  state: AuthState
  register: (request: RegisterRequest) => Promise<UserProfile>
  login: (request: LoginRequest) => Promise<UserProfile>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
