package com.casino.wallet;

import java.util.UUID;

import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.entity.Wallet;
import com.casino.wallet.repository.WalletRepository;
import com.casino.wallet.service.WalletService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Players with wallets for wallet integration tests. Users are inserted directly, bypassing
 * registration (which opens wallets with the starting balance), so each test sets exactly the balance
 * it needs; balances are funded with the test-only type {@code TEST_CREDIT}.
 */
public final class WalletTestData {

    private final JdbcTemplate jdbcTemplate;
    private final WalletRepository wallets;
    private final WalletService walletService;
    private final TransactionTemplate transactions;

    public WalletTestData(JdbcTemplate jdbcTemplate, WalletRepository wallets, WalletService walletService,
                          TransactionTemplate transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.wallets = wallets;
        this.walletService = walletService;
        this.transactions = transactions;
    }

    /** A player without a wallet. */
    public UUID player(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at)
                VALUES (?, '{noop}not-used', 'Player', now()) RETURNING id""", UUID.class, email);
    }

    /** A player with a wallet holding {@code balance} coins (one {@code TEST_CREDIT} entry, none for 0). */
    public UUID playerWithBalance(String email, long balance) {
        UUID user = player(email);
        transactions.executeWithoutResult(status -> wallets.save(new Wallet(user)));
        if (balance > 0) {
            walletService.credit(user, new WalletOperation(WalletTransactionType.TEST_CREDIT, balance, "funding", null));
        }
        return user;
    }

    /** {@code balance} of the wallet, read directly. */
    public long balance(UUID user) {
        return jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", Long.class, user);
    }

    /** Number of ledger entries of the user's wallet. */
    public long entries(UUID user) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ?""",
                Long.class, user);
    }

    /**
     * Wallets whose balance differs from the sum of their entries or from the newest {@code balance_after}
     * (the verification query of {@code docs/plans/week-03.md} §6.7); must be 0.
     */
    public long inconsistentWallets() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM wallets w
                WHERE w.balance <> (SELECT coalesce(sum(t.amount), 0) FROM wallet_transactions t WHERE t.wallet_id = w.id)
                   OR w.balance <> coalesce((SELECT t.balance_after FROM wallet_transactions t WHERE t.wallet_id = w.id
                                             ORDER BY t.id DESC LIMIT 1), 0)""", Long.class);
    }
}
