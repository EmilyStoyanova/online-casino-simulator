package com.casino.wallet.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the append-only ledger; never the idempotency key, the reference, the wallet id or
 * the user id (AC-3.5).
 *
 * <p>Contract: {@code WalletTransaction} schema in {@code openapi.yaml}.
 *
 * @param id           entry id; higher ids are newer, ids can have gaps
 * @param amount       signed change in whole coins (positive credit, negative debit)
 * @param balanceAfter balance right after this entry
 */
@Schema(name = "WalletTransaction")
public record WalletTransactionResponse(
        @Schema(requiredMode = REQUIRED, format = "int64") long id,
        @Schema(requiredMode = REQUIRED) WalletEntryType type,
        @Schema(requiredMode = REQUIRED, format = "int64") long amount,
        @Schema(requiredMode = REQUIRED, format = "int64") long balanceAfter,
        @Schema(requiredMode = REQUIRED) Instant createdAt) {
}
