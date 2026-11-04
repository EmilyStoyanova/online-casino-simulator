import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, onSessionExpired } from '../../services/apiClient'
import * as authApi from '../../services/authApi'
import { getCurrentUser } from '../../services/userApi'
import type { LoginRequest, RegisterRequest } from '../../types/auth'
import { AuthContext, type AuthContextValue, type AuthState } from './authContext'

/** Only a `401` confirms that there is no session; any other failure leaves the state unknown. */
function stateAfterFailedCheck(error: unknown): AuthState {
  if (error instanceof ApiError && error.status === 401) {
    return { status: 'anonymous', reason: error.code === 'SESSION_EXPIRED' ? 'session-expired' : 'signed-out' }
  }
  return { status: 'unknown' }
}

/**
 * Holds the authentication state in memory (see {@link AuthState}). Must be rendered inside the
 * router.
 *
 * On start-up it asks the server who is signed in (`GET /api/v1/users/me`), so a session survives a
 * page reload. A `401 SESSION_EXPIRED` while the user was signed in leads to the login page with an
 * explanation; at start-up the user is simply shown as signed out, with the same explanation.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'checking' })
  const [checkRequest, setCheckRequest] = useState(0)
  const navigate = useNavigate()
  const stateRef = useRef(state)
  // Incremented by every sign-in and sign-out, so a slower start-up check cannot overwrite them.
  const generation = useRef(0)

  useEffect(() => {
    stateRef.current = state
  }, [state])

  useEffect(() => {
    const controller = new AbortController()
    const started = generation.current
    const current = (): boolean => !controller.signal.aborted && generation.current === started

    getCurrentUser({ signal: controller.signal })
      .then((user) => {
        if (current()) setState({ status: 'authenticated', user })
      })
      .catch((error: unknown) => {
        if (current()) setState(stateAfterFailedCheck(error))
      })

    return () => controller.abort()
  }, [checkRequest])

  useEffect(
    () =>
      onSessionExpired(() => {
        const wasSignedIn = stateRef.current.status === 'authenticated'
        setState({ status: 'anonymous', reason: 'session-expired' })
        if (wasSignedIn) navigate('/login')
      }),
    [navigate],
  )

  const register = useCallback(async (request: RegisterRequest) => {
    const user = await authApi.register(request)
    generation.current += 1
    setState({ status: 'authenticated', user })
    return user
  }, [])

  const login = useCallback(async (request: LoginRequest) => {
    const user = await authApi.login(request)
    generation.current += 1
    setState({ status: 'authenticated', user })
    return user
  }, [])

  const logout = useCallback(async () => {
    await authApi.logout()
    generation.current += 1
    setState({ status: 'anonymous', reason: 'signed-out' })
  }, [])

  const checkSession = useCallback(() => {
    setState({ status: 'checking' })
    setCheckRequest((request) => request + 1)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ state, register, login, logout, checkSession }),
    [state, register, login, logout, checkSession],
  )
  return <AuthContext value={value}>{children}</AuthContext>
}
