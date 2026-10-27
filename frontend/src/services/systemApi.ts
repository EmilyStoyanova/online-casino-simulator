import type { SystemStatus } from '../types/system'
import { ApiError, apiGet, type RequestOptions } from './apiClient'

const STATUS_PATH = '/api/v1/system/status'

function isSystemStatus(value: unknown): value is SystemStatus {
  return typeof value === 'object' && value !== null && 'status' in value && 'database' in value
}

/**
 * Fetches the platform status. A `503` still carries a valid status payload (e.g. database
 * down), so it is returned as data instead of being treated as a failure.
 */
export async function fetchSystemStatus(options: RequestOptions = {}): Promise<SystemStatus> {
  try {
    return await apiGet<SystemStatus>(STATUS_PATH, options)
  } catch (error) {
    if (error instanceof ApiError && error.status === 503 && isSystemStatus(error.body)) {
      return error.body
    }
    throw error
  }
}
