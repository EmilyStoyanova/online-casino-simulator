import { useCallback, useState } from 'react'
import { apiErrorMessage } from '../../i18n/serverMessages'
import { msg, type Message } from '../../i18n/translate'
import { ApiError } from '../../services/apiClient'
import { useAuth } from './useAuth'

/**
 * Logout with its pending and error state, shared by the header and the account panel. A successful
 * logout ends on the home page (`AuthProvider`). The error is a message, translated when shown.
 */
export function useSignOut() {
  const { logout } = useAuth()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Message | null>(null)

  const signOut = useCallback(async () => {
    setPending(true)
    setError(null)
    try {
      await logout()
    } catch (failure) {
      setError(failure instanceof ApiError && failure.isUnavailable ? apiErrorMessage(failure) : msg('signOut.failed'))
    } finally {
      setPending(false)
    }
  }, [logout])

  return { signOut, pending, error }
}
