import type { Wallet, WalletTransactionPage, WalletTransactionQuery } from '../types/wallet'
import { apiGet, type RequestOptions } from './apiClient'

const WALLET_PATH = '/api/v1/wallet'
const TRANSACTIONS_PATH = '/api/v1/wallet/transactions'

/**
 * The signed-in player's balance (`Wallet` tag of `openapi.yaml`). Read-only; the server identifies the
 * wallet by the session, never by a parameter. `403 ACCESS_DENIED` for administrators, who have no
 * wallet.
 */
export function getWallet(options: RequestOptions = {}): Promise<Wallet> {
  return apiGet<Wallet>(WALLET_PATH, options)
}

/**
 * One page of the ledger, newest first. Pass the previous page's `nextCursor` unchanged as `cursor` to
 * get the next (older) page; omitted values use the server defaults (`limit` 20).
 */
export function listWalletTransactions(
  query: WalletTransactionQuery = {},
  options: RequestOptions = {},
): Promise<WalletTransactionPage> {
  const params = new URLSearchParams()
  if (query.limit !== undefined) params.set('limit', String(query.limit))
  if (query.cursor !== undefined) params.set('cursor', query.cursor)
  const search = params.toString()
  return apiGet<WalletTransactionPage>(search === '' ? TRANSACTIONS_PATH : `${TRANSACTIONS_PATH}?${search}`, options)
}
