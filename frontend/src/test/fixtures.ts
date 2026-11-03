import type { SystemStatus } from '../types/system'
import type { UserProfile } from '../types/user'

/** The `UserProfile` the backend returns for a new player (contract example). */
export const alice: UserProfile = {
  id: '0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10',
  email: 'alice@example.com',
  displayName: 'Alice',
  role: 'PLAYER',
  interests: [],
  adultConfirmedAt: '2026-11-02T09:15:00Z',
  createdAt: '2026-11-02T09:15:00Z',
}

export const upStatus: SystemStatus = {
  application: 'online-casino-simulator',
  version: '0.1.0-SNAPSHOT',
  status: 'UP',
  database: { status: 'UP', schemaVersion: '5' },
  timestamp: '2026-11-03T10:00:00Z',
}

export const PASSWORD = 'correct-horse-battery-staple'
