/**
 * Mirrors the roulette schemas of `openapi.yaml` (0.5.0, tag `Roulette`): `RouletteSpinRequest`,
 * `RouletteBet`, `RouletteBetCategory` and its option enums, `RouletteSpinResult`,
 * `RouletteSettledBet` and `RouletteNumberColor`.
 *
 * The request carries bets only. The server draws the number, computes every payout and the balance,
 * and validates every limit again; the constants below exist for user feedback, not for authority.
 */

export const ROULETTE_BET_CATEGORIES = ['STRAIGHT', 'RED_BLACK', 'EVEN_ODD', 'LOW_HIGH', 'DOZEN'] as const
export const ROULETTE_BET_COLORS = ['RED', 'BLACK'] as const
export const ROULETTE_PARITIES = ['EVEN', 'ODD'] as const
export const ROULETTE_RANGES = ['LOW', 'HIGH'] as const
export const ROULETTE_DOZENS = ['FIRST', 'SECOND', 'THIRD'] as const
export const ROULETTE_NUMBER_COLORS = ['RED', 'BLACK', 'GREEN'] as const

export type RouletteBetCategory = (typeof ROULETTE_BET_CATEGORIES)[number]
/** Option of a `RED_BLACK` bet; zero (green) cannot be chosen. */
export type RouletteBetColor = (typeof ROULETTE_BET_COLORS)[number]
/** Option of an `EVEN_ODD` bet; zero is neither. */
export type RouletteParity = (typeof ROULETTE_PARITIES)[number]
/** Option of a `LOW_HIGH` bet: `LOW` = 1–18, `HIGH` = 19–36. */
export type RouletteRange = (typeof ROULETTE_RANGES)[number]
/** Option of a `DOZEN` bet: `FIRST` = 1–12, `SECOND` = 13–24, `THIRD` = 25–36. */
export type RouletteDozen = (typeof ROULETTE_DOZENS)[number]
/** Colour of a winning number; `GREEN` only for 0. */
export type RouletteNumberColor = (typeof ROULETTE_NUMBER_COLORS)[number]

/** Limits of the contract (`RouletteSpinRequest`, `RouletteBet`); the server enforces them. */
export const ROULETTE_LIMITS = {
  /** Distinct bets per spin. */
  minBets: 1,
  maxBets: 10,
  /** Whole coins per bet. */
  minAmount: 1,
  maxAmount: 1000,
  /** Sum of the amounts of one spin (also at most the balance, checked by the server). */
  maxTotalStake: 10_000,
  /** Numbers of a `STRAIGHT` bet. */
  minNumber: 0,
  maxNumber: 36,
} as const

/**
 * A category with exactly its one option property. The contract allows the other option properties to
 * be omitted or `null`; the SPA always omits them, and the server omits them in results.
 */
export type RouletteSelection =
  | { category: 'STRAIGHT'; number: number }
  | { category: 'RED_BLACK'; color: RouletteBetColor }
  | { category: 'EVEN_ODD'; parity: RouletteParity }
  | { category: 'LOW_HIGH'; range: RouletteRange }
  | { category: 'DOZEN'; dozen: RouletteDozen }

/** One bet of a spin request: a selection and a whole-coin amount (1–1,000). */
export type RouletteBet = RouletteSelection & { amount: number }

/**
 * Body of `POST /api/v1/games/roulette/spins`: 1–10 bets with different selections. Read-only, because
 * a spin attempt sends one frozen body unchanged on every retry with its `Idempotency-Key`.
 */
export interface RouletteSpinRequest {
  readonly bets: readonly Readonly<RouletteBet>[]
}

/** A bet of a played round, as stored by the server. */
export type RouletteSettledBet = RouletteSelection & {
  amount: number
  /** Coins returned for this bet: stake plus net winnings on a win, `0` on a loss (server-computed). */
  payout: number
}

/** `200` of the spin, for a new round and for a replay of the same `Idempotency-Key`. */
export interface RouletteSpinResult {
  /** Server-generated; the same for a replayed request. */
  roundId: string
  /** 0–36, drawn by the server. */
  winningNumber: number
  winningColor: RouletteNumberColor
  /** Every bet with its payout, in the order of the original request. */
  bets: RouletteSettledBet[]
  totalStake: number
  totalPayout: number
  /** Balance right after this round (after the payout, if any). */
  balance: number
  /** When the round was played (ISO-8601, UTC). */
  createdAt: string
}
