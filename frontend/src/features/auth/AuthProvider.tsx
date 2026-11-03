import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { onSessionExpired } from '../../services/apiClient'
import * as authApi from '../../services/authApi'
import type { LoginRequest, RegisterRequest } from '../../types/auth'
import { AuthContext, type AuthContextValue, type AuthState } from './authContext'

/**
 * Holds the authentication state in memory (see {@link AuthState}). Must be rendered inside the
 * router: a `401 SESSION_EXPIRED` from any request leads to the login page with an explanation.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'unknown' })
  const navigate = useNavigate()

  useEffect(
    () =>
      onSessionExpired(() => {
        setState({ status: 'anonymous', reason: 'session-expired' })
        navigate('/login')
      }),
    [navigate],
  )

  const register = useCallback(async (request: RegisterRequest) => {
    const user = await authApi.register(request)
    setState({ status: 'authenticated', user })
    return user
  }, [])

  const login = useCallback(async (request: LoginRequest) => {
    const user = await authApi.login(request)
    setState({ status: 'authenticated', user })
    return user
  }, [])

  const logout = useCallback(async () => {
    await authApi.logout()
    setState({ status: 'anonymous', reason: 'signed-out' })
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ state, register, login, logout }),
    [state, register, login, logout],
  )
  return <AuthContext value={value}>{children}</AuthContext>
}
