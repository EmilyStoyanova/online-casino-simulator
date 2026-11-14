package com.casino.wallet.dto;

/**
 * Reason for a balance change, given by the calling module (schema {@code WalletTransactionType}).
 * Each value has a fixed direction: {@code WalletService.credit} accepts only credit types and
 * {@code debit} only debit types. Later weeks add their values (game stakes and payouts, learning
 * rewards) together with a migration and a contract change.
 */
public enum WalletTransactionType {

    /** Starting coins, credited exactly once when a wallet is opened. */
    STARTING_BALANCE(true),

    /**
     * <b>Test support only.</b> Until the first real debit type (Week 4), tests need some type to
     * exercise credits and debits against the real schema. The production schema rejects both test
     * types ({@code ck_wallet_transactions_type}); a test-only Flyway callback
     * ({@code src/test/resources/db/test-callbacks/afterMigrate.sql}) allows them in test databases
     * only. Production code must not use them (ArchUnit R15), and the API never returns them.
     */
    TEST_CREDIT(true),

    /** Test support only, like {@link #TEST_CREDIT}, for debits. */
    TEST_DEBIT(false);

    private final boolean credit;

    WalletTransactionType(boolean credit) {
        this.credit = credit;
    }

    /** Whether operations of this type add coins; otherwise they remove coins. */
    public boolean isCredit() {
        return credit;
    }
}
