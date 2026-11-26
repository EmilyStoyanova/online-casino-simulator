import { useCallback, useEffect, useState } from 'react'
import { apiErrorMessage } from '../../i18n/serverMessages'
import { msg, type Message } from '../../i18n/translate'
import { ApiError } from '../../services/apiClient'
import { fetchSystemStatus } from '../../services/systemApi'
import type { SystemStatus } from '../../types/system'
import { noteSchemaVersion } from '../roulette/rouletteAvailability'

export type SystemStatusState =
  | { kind: 'loading' }
  | { kind: 'success'; data: SystemStatus }
  | { kind: 'error'; message: Message }

export function useSystemStatus(): { state: SystemStatusState; refresh: () => void } {
  const [state, setState] = useState<SystemStatusState>({ kind: 'loading' })
  const [requestId, setRequestId] = useState(0)

  useEffect(() => {
    const controller = new AbortController()

    fetchSystemStatus({ signal: controller.signal })
      .then((data) => {
        // A schema below V9 tells the roulette page early that this backend has no roulette (§7.7).
        noteSchemaVersion(data.database.schemaVersion)
        setState({ kind: 'success', data })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        const message = error instanceof ApiError ? apiErrorMessage(error) : msg('status.unexpected')
        setState({ kind: 'error', message })
      })

    return () => controller.abort()
  }, [requestId])

  const refresh = useCallback(() => {
    setState({ kind: 'loading' })
    setRequestId((id) => id + 1)
  }, [])

  return { state, refresh }
}
