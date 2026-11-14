package com.casino.wallet.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The signed-in player's wallet. Coins have no monetary value.
 *
 * <p>Contract: {@code Wallet} schema in {@code openapi.yaml}.
 *
 * @param balance   current balance in whole virtual coins (0 to 2^53 - 1)
 * @param updatedAt when the balance last changed (set by the database)
 */
@Schema(name = "Wallet")
public record WalletResponse(
        @Schema(requiredMode = REQUIRED, format = "int64") long balance,
        @Schema(requiredMode = REQUIRED) Instant updatedAt) {
}
