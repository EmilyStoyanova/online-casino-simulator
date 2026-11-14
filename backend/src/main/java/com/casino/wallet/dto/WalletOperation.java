package com.casino.wallet.dto;

/**
 * A balance change requested by another module (or by the wallet itself for the starting balance).
 * Validated by {@code WalletService} before anything is locked or written.
 *
 * @param type           reason; its direction must match the method ({@code credit}/{@code debit})
 * @param amount         whole coins, positive; the sign comes from the method
 * @param idempotencyKey key of the operation, chosen by the calling module and deterministic for it
 *                       (e.g. {@code starting-balance}); unique per wallet; pattern
 *                       {@code ^[a-z0-9][a-z0-9:._-]{0,99}$}. Never raw client input.
 * @param reference      optional id of the cause (e.g. a game round), at most 100 characters, or
 *                       {@code null}
 */
public record WalletOperation(WalletTransactionType type, long amount, String idempotencyKey, String reference) {
}
