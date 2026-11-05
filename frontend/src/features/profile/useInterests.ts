import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../../services/apiClient'
import { listInterests } from '../../services/interestApi'
import type { Interest } from '../../types/interest'

/** The interest catalogue (`GET /api/v1/interests`): active interests in display order. */
export type InterestCatalog =
  | { status: 'loading' }
  | { status: 'loaded'; interests: Interest[] }
  | { status: 'failed'; message: string }

export function useInterests(): { catalog: InterestCatalog; reload: () => void } {
  const [catalog, setCatalog] = useState<InterestCatalog>({ status: 'loading' })
  const [requestId, setRequestId] = useState(0)

  useEffect(() => {
    const controller = new AbortController()

    listInterests({ signal: controller.signal })
      .then((interests) => setCatalog({ status: 'loaded', interests }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        const message =
          error instanceof ApiError && error.isNetworkError
            ? error.message
            : 'The list of interests could not be loaded.'
        setCatalog({ status: 'failed', message })
      })

    return () => controller.abort()
  }, [requestId])

  const reload = useCallback(() => {
    setCatalog({ status: 'loading' })
    setRequestId((id) => id + 1)
  }, [])

  return { catalog, reload }
}
