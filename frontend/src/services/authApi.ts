import type { LoginRequest, RegisterRequest } from '../types/auth'
import type { UserProfile } from '../types/user'
import { apiPost, refreshCsrfToken, sessionChanged } from './apiClient'

const REGISTER_PATH = '/api/v1/auth/register'
const LOGIN_PATH = '/api/v1/auth/login'
const LOGOUT_PATH = '/api/v1/auth/logout'

/**
 * Registration, login and logout (`Authentication` tag of `openapi.yaml`). The session lives in an
 * `HttpOnly` cookie set by the server; nothing is stored in the browser by this code.
 *
 * Each successful call deletes the `XSRF-TOKEN` cookie on the server side, so a new token is
 * fetched right away, as the contract requires. Each one also replaces the session, so a late
 * `SESSION_EXPIRED` of an earlier request is no longer reported ({@link sessionChanged}).
 */
export async function register(request: RegisterRequest): Promise<UserProfile> {
  const profile = await apiPost<UserProfile>(REGISTER_PATH, request)
  sessionChanged()
  await renewCsrfToken()
  return profile
}

export async function login(request: LoginRequest): Promise<UserProfile> {
  const profile = await apiPost<UserProfile>(LOGIN_PATH, request)
  sessionChanged()
  await renewCsrfToken()
  return profile
}

/** Ends the session, if there is one (`204` also without a session). */
export async function logout(): Promise<void> {
  await apiPost<void>(LOGOUT_PATH)
  sessionChanged()
  await renewCsrfToken()
}

async function renewCsrfToken(): Promise<void> {
  try {
    await refreshCsrfToken()
  } catch {
    // Not fatal: the next unsafe request fetches a token itself.
  }
}
