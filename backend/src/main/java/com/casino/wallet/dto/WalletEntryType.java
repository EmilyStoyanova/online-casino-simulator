package com.casino.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Type of a ledger entry as the API shows it: exactly the values of the contract schema
 * {@code WalletTransactionType} (published under that name). Deliberately separate from the command
 * type {@link WalletTransactionType}, which also has the test-only values: only values listed here can
 * ever appear in a response or in the generated API documentation.
 *
 * <p>Each week that adds a stored type adds its value here together with the contract change
 * ({@code WalletModelTest} checks that every stored type except the test-only ones has one).
 */
@Schema(name = "WalletTransactionType", enumAsRef = true)
public enum WalletEntryType {

    /** Starting coins, granted exactly once when the wallet is opened. */
    STARTING_BALANCE
}
