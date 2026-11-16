package com.casino.wallet.entity;

/**
 * Reason for a ledger entry as stored ({@code wallet_transactions.type}). Mirrors
 * {@code wallet.dto.WalletTransactionType}, the public type that other modules use (same constants,
 * checked by {@code WalletModelTest}). Each value has a fixed direction, also enforced by the
 * database ({@code ck_wallet_transactions_type}, {@code ck_wallet_transactions_direction}, V8).
 */
public enum TransactionType {

    /** Starting coins, credited exactly once when a wallet is opened. */
    STARTING_BALANCE(true),

    /** Total stake of one roulette round (Week 4). */
    ROULETTE_STAKE(false),

    /** Total payout of one roulette round, only when something was won (Week 4). */
    ROULETTE_PAYOUT(true);

    private final boolean credit;

    TransactionType(boolean credit) {
        this.credit = credit;
    }

    /** Whether entries of this type add coins ({@code amount > 0}); otherwise they remove coins. */
    public boolean isCredit() {
        return credit;
    }
}
