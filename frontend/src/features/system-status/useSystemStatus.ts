import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../../services/apiClient'
import { fetchSystemStatus } from '../../services/systemApi'
import type { SystemStatus } from '../../types/system'

export type SystemStatusState =
  | { kind: 'loading' }
  | { kind: 'success'; data: SystemStatus }
  | { kind: 'error'; message: string }

export function useSystemStatus(): { state: SystemStatusState; refresh: () => void } {
  const [state, setState] = useState<SystemStatusState>({ kind: 'loading' })
  const [requestId, setRequestId] = useState(0)

  useEffect(() => {
    const controller = new AbortController()

    fetchSystemStatus({ signal: controller.signal })
      .then((data) => setState({ kind: 'success', data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        const message =
          error instanceof ApiError ? error.message : 'Unexpected error while contacting the server.'
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
