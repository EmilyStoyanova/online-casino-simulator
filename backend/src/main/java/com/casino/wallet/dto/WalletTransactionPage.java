package com.casino.wallet.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of ledger entries, newest first.
 *
 * <p>Contract: {@code WalletTransactionPage} schema in {@code openapi.yaml}.
 *
 * @param items      at most {@code limit} entries, ordered by id descending; empty for an empty history
 * @param nextCursor opaque cursor of the next (older) page; always present, {@code null} on the last page
 */
@Schema(name = "WalletTransactionPage")
public record WalletTransactionPage(
        @Schema(requiredMode = REQUIRED) List<WalletTransactionResponse> items,
        @Schema(requiredMode = REQUIRED, nullable = true) String nextCursor) {

    public WalletTransactionPage {
        items = List.copyOf(items);
    }
}
