package com.casino.wallet.entity;

/**
 * Reason for a ledger entry as stored ({@code wallet_transactions.type}). Mirrors
 * {@code wallet.dto.WalletTransactionType}, the public type that other modules use (same constants,
 * checked by {@code TransactionTypeTest}). Each value has a fixed direction, also enforced by the
 * database ({@code ck_wallet_transactions_direction}).
 */
public enum TransactionType {

    /** Starting coins, credited exactly once when a wallet is opened. */
    STARTING_BALANCE(true),

    /**
     * Test support only (see {@code WalletTransactionType.TEST_CREDIT}): rejected by the production
     * schema ({@code ck_wallet_transactions_type}); production code never uses it (ArchUnit R15).
     */
    TEST_CREDIT(true),

    /** Test support only, like {@link #TEST_CREDIT}, for debits. */
    TEST_DEBIT(false);

    private final boolean credit;

    TransactionType(boolean credit) {
        this.credit = credit;
    }

    /** Whether entries of this type add coins ({@code amount > 0}); otherwise they remove coins. */
    public boolean isCredit() {
        return credit;
    }
}
