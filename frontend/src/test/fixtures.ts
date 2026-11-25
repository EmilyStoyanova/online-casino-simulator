import type { Interest } from '../types/interest'
import type { RouletteSpinRequest, RouletteSpinResult } from '../types/roulette'
import type { SystemStatus } from '../types/system'
import type { UserProfile } from '../types/user'
import type { Wallet, WalletTransactionPage } from '../types/wallet'

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

/** The `UserProfile` of an administrator (created by the bootstrap; no wallet). */
export const admin: UserProfile = {
  id: '5d6e7f80-1a2b-4c3d-8e9f-a0b1c2d3e4f5',
  email: 'admin@example.com',
  displayName: 'Admin',
  role: 'ADMIN',
  interests: [],
  adultConfirmedAt: '2026-11-01T08:00:00Z',
  createdAt: '2026-11-01T08:00:00Z',
}

export const upStatus: SystemStatus = {
  application: 'online-casino-simulator',
  version: '0.1.0-SNAPSHOT',
  status: 'UP',
  // V9: the Week 4 backend with the roulette and wallet tables (a lower version hides roulette, §7.7).
  database: { status: 'UP', schemaVersion: '9' },
  timestamp: '2026-11-03T10:00:00Z',
}

export const PASSWORD = 'correct-horse-battery-staple'

/** `GET /api/v1/wallet` of a new player (contract example). */
export const startingWallet: Wallet = { balance: 1000, updatedAt: '2026-11-12T09:15:00.123456Z' }

/** A canonical idempotency key (contract example). */
export const SPIN_KEY = '3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b'

/** The request of the contract example: a straight bet on 17 and a bet on red. */
export const spinRequest: RouletteSpinRequest = {
  bets: [
    { category: 'STRAIGHT', number: 17, amount: 10 },
    { category: 'RED_BLACK', color: 'RED', amount: 20 },
  ],
}

/** Its `200` (contract example): 17 is black, so the straight bet wins and the red bet loses. */
export const spinResult: RouletteSpinResult = {
  roundId: '6f1c2a0e-8b3d-4c55-9a7e-2f0d5b1e9c41',
  winningNumber: 17,
  winningColor: 'BLACK',
  bets: [
    { category: 'STRAIGHT', number: 17, amount: 10, payout: 360 },
    { category: 'RED_BLACK', color: 'RED', amount: 20, payout: 0 },
  ],
  totalStake: 30,
  totalPayout: 360,
  balance: 1330,
  createdAt: '2026-11-20T10:15:30.123456Z',
}

/** `GET /api/v1/wallet/transactions` after that round (contract example), newest first. */
export const historyAfterSpin: WalletTransactionPage = {
  items: [
    { id: 3, type: 'ROULETTE_PAYOUT', amount: 360, balanceAfter: 1330, createdAt: '2026-11-20T10:15:30.118204Z' },
    { id: 2, type: 'ROULETTE_STAKE', amount: -30, balanceAfter: 970, createdAt: '2026-11-20T10:15:30.118204Z' },
    { id: 1, type: 'STARTING_BALANCE', amount: 1000, balanceAfter: 1000, createdAt: '2026-11-12T09:15:00Z' },
  ],
  nextCursor: null,
}

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
