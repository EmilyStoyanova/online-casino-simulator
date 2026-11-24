import { useContext } from 'react'
import { WalletContext, type WalletContextValue } from './walletContext'

/** The signed-in player's wallet state and its two operations (see `WalletProvider`). */
export function useWallet(): WalletContextValue {
  return useContext(WalletContext)
}
