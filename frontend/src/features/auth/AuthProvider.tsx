import { startTransition, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError, onSessionExpired } from '../../services/apiClient'
import * as authApi from '../../services/authApi'
import { getCurrentUser } from '../../services/userApi'
import type { LoginRequest, RegisterRequest } from '../../types/auth'
import { AuthContext, type AuthContextValue, type AuthState } from './authContext'
import { loginPath } from './returnPath'

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
 * explanation, which returns to the current page after signing in again; at start-up the user is
 * simply shown as signed out, with the same explanation (`RequireAuth` sends them to the login page
 * from a protected page).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'checking' })
  const [checkRequest, setCheckRequest] = useState(0)
  const navigate = useNavigate()
  const location = useLocation()
  const stateRef = useRef(state)
  // The current page, read when the session ends (the listener is registered once).
  const hereRef = useRef('/')
  // Incremented by every sign-in and sign-out, so a slower start-up check cannot overwrite them.
  const generation = useRef(0)

  useEffect(() => {
    stateRef.current = state
  }, [state])

  useEffect(() => {
    hereRef.current = `${location.pathname}${location.search}${location.hash}`
  }, [location])

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
        // One transition, like the router's own navigations: the old page is never rendered as signed out.
        startTransition(() => {
          setState({ status: 'anonymous', reason: 'session-expired' })
          if (wasSignedIn) navigate(loginPath(hereRef.current))
        })
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
    // Signing out ends on the home page. State and location change in one transition (the router
    // navigates in transitions), so a protected page is not rendered signed out and `RequireAuth`
    // does not send the user to the login page first.
    startTransition(() => {
      setState({ status: 'anonymous', reason: 'signed-out' })
      navigate('/')
    })
  }, [navigate])

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
