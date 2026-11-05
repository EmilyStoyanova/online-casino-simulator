import type { Interest } from '../types/interest'
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

/** `GET /api/v1/interests` as seeded by migration V5, in display order. */
export const interestCatalog: Interest[] = [
  { code: 'PROBABILITY', name: 'Probability & odds', description: 'How likely outcomes are and how odds are expressed' },
  { code: 'MATHEMATICS', name: 'Mathematics of games', description: 'Expected value, house edge and variance' },
  { code: 'STATISTICS', name: 'Statistics & data', description: 'Reading results, randomness and the law of large numbers' },
  { code: 'GAME_THEORY', name: 'Game theory & strategy', description: 'Decision-making under uncertainty' },
  { code: 'PSYCHOLOGY', name: 'Psychology of decisions', description: "Cognitive biases such as the gambler's fallacy" },
  { code: 'FINANCIAL_LITERACY', name: 'Financial literacy', description: 'Budgeting, risk and personal finance basics' },
  { code: 'HISTORY', name: 'History of games', description: 'Origins and evolution of casino games' },
  {
    code: 'RESPONSIBLE_PLAY',
    name: 'Responsible play',
    description: 'Healthy habits, limits and self-awareness (informational, not clinical)',
  },
]
