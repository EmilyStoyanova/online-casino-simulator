import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../../services/apiClient'
import { useAuth } from './useAuth'

/** Logout with its pending and error state, shared by the header and the account panel. */
export function useSignOut() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const signOut = useCallback(async () => {
    setPending(true)
    setError(null)
    try {
      await logout()
      navigate('/')
    } catch (failure) {
      setError(
        failure instanceof ApiError && failure.isNetworkError
          ? failure.message
          : 'Signing out failed. Please try again.',
      )
    } finally {
      setPending(false)
    }
  }, [logout, navigate])

  return { signOut, pending, error }
}
