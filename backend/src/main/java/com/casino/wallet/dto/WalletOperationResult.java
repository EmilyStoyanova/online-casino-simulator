package com.casino.wallet.dto;

/**
 * Outcome of a credit or debit.
 *
 * @param transactionId id of the ledger entry of the operation
 * @param balanceAfter  balance right after that entry
 * @param replayed      {@code true} when the operation had already been applied with the same key and
 *                      values: the original result is returned and nothing changed
 */
public record WalletOperationResult(long transactionId, long balanceAfter, boolean replayed) {
}
