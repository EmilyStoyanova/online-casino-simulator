package com.casino.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Type of a ledger entry as the API shows it: exactly the values of the contract schema
 * {@code WalletTransactionType} (published under that name). Separate from the command type
 * {@link WalletTransactionType} so that only values listed here can ever appear in a response or in
 * the generated API documentation.
 *
 * <p>{@code WalletService} maps every stored type to its value with an exhaustive {@code switch}, so a
 * new stored type does not compile without its public value; {@code WalletModelTest} keeps the names
 * equal. Each week that adds a stored type adds its value here together with the contract change.
 */
@Schema(name = "WalletTransactionType", enumAsRef = true)
public enum WalletEntryType {

    /** Starting coins, granted exactly once when the wallet is opened. */
    STARTING_BALANCE,

    /** Total stake of one roulette round (debit). */
    ROULETTE_STAKE,

    /** Total payout of one roulette round, only when something was won (credit). */
    ROULETTE_PAYOUT
}
