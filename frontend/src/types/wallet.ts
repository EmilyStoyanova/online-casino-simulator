/**
 * Mirrors the `Wallet`, `WalletTransaction`, `WalletTransactionType` and `WalletTransactionPage`
 * schemas of `openapi.yaml` (0.5.0, tag `Wallet`). Coins are whole numbers from 0 to 2^53 − 1, exact in
 * JavaScript. Balances change on the server only; the SPA displays what the server returns.
 */

/** Ledger entry types published by the contract. */
export const WALLET_TRANSACTION_TYPES = ['STARTING_BALANCE', 'ROULETTE_STAKE', 'ROULETTE_PAYOUT'] as const

export type WalletTransactionType = (typeof WALLET_TRANSACTION_TYPES)[number]

/** `GET /api/v1/wallet`: the signed-in player's wallet. */
export interface Wallet {
  /** Whole virtual coins, 0 … 2^53 − 1. */
  balance: number
  /** When the balance last changed (ISO-8601, UTC). */
  updatedAt: string
}

/** One entry of the append-only ledger. */
export interface WalletTransaction {
  /** Higher ids are newer; ids can have gaps. */
  id: number
  /**
   * Later contract versions add types. Clients treat an unknown value as a generic entry and use the
   * sign of `amount` (contract rule since 0.4.0).
   */
  type: WalletTransactionType | (string & {})
  /** Positive for a credit, negative for a debit, never `0`. */
  amount: number
  /** Balance right after this entry. */
  balanceAfter: number
  /** ISO-8601, UTC; entries of one operation share it. */
  createdAt: string
}

/** `GET /api/v1/wallet/transactions`: one page, newest first. */
export interface WalletTransactionPage {
  items: WalletTransaction[]
  /** Opaque cursor of the next (older) page, passed back unchanged; `null` on the last page. */
  nextCursor: string | null
}

/** Query of `GET /api/v1/wallet/transactions`; omitted values use the server defaults. */
export interface WalletTransactionQuery {
  /** 1–100, default 20. */
  limit?: number
  /** `nextCursor` of the previous page. */
  cursor?: string
}

/** Page size limits of the history (contract parameter `limit`). */
export const WALLET_HISTORY_LIMITS = { defaultLimit: 20, minLimit: 1, maxLimit: 100 } as const
