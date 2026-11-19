package com.casino.wallet.dto;

/**
 * Reason for a balance change, given by the calling module (schema {@code WalletTransactionType}).
 * Each value has a fixed direction: {@code WalletService.credit} accepts only credit types and
 * {@code debit} only debit types; {@code STARTING_BALANCE} is granted only when a wallet is opened.
 * Later weeks add their values (learning rewards) together with a migration and a contract change.
 */
public enum WalletTransactionType {

    /** Starting coins, credited exactly once when a wallet is opened ({@code WalletService.openWallet}). */
    STARTING_BALANCE(true),

    /** Total stake of one roulette round: a debit (Week 4). */
    ROULETTE_STAKE(false),

    /** Total payout of one roulette round, only when something was won: a credit (Week 4). */
    ROULETTE_PAYOUT(true);

    private final boolean credit;

    WalletTransactionType(boolean credit) {
        this.credit = credit;
    }

    /** Whether operations of this type add coins; otherwise they remove coins. */
    public boolean isCredit() {
        return credit;
    }
}
