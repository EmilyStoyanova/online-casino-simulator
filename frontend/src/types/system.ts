/** Mirrors `SystemStatusResponse` from the backend (`GET /api/v1/system/status`). */
export type ComponentStatus = 'UP' | 'DOWN'

export interface SystemStatus {
  application: string
  version: string
  status: ComponentStatus
  database: {
    status: ComponentStatus
    schemaVersion: string | null
  }
  timestamp: string
}
