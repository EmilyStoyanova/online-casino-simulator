package com.casino.wallet;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * it needs; balances are funded with one {@code ROULETTE_PAYOUT} credit (a real credit type, W4-03).
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

    /** A player whose wallet was opened as at registration: the starting balance and its single entry. */
    public UUID openedPlayer(String email) {
        UUID user = player(email);
        walletService.openWallet(user);
        return user;
    }

    /** A player with a wallet holding {@code balance} coins (one {@code ROULETTE_PAYOUT} entry, none for 0). */
    public UUID playerWithBalance(String email, long balance) {
        UUID user = player(email);
        transactions.executeWithoutResult(status -> wallets.save(new Wallet(user)));
        if (balance > 0) {
            walletService.credit(user, new WalletOperation(WalletTransactionType.ROULETTE_PAYOUT, balance, "funding", null));
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
     * Violations of the ledger invariants, by name; every count must be 0 (W3-09):
     * <ul>
     *   <li>{@code balance <> Σ amount} or {@code balance <> newest balance_after};</li>
     *   <li>an entry whose {@code balance_after} is not the running sum of its wallet's amounts up to it
     *       (in id order): every entry was appended to the balance it saw, none was lost or reordered;</li>
     *   <li>a balance or {@code balance_after} outside 0 … 2^53 - 1, a zero amount;</li>
     *   <li>more than one wallet per user, a wallet of a non-player, a key used twice in a wallet.</li>
     * </ul>
     * Independent of how the balances were funded (unlike {@link #walletsNotOpenedByTheStartingEntry}).
     */
    public Map<String, Long> ledgerViolations() {
        Map<String, Long> violations = new LinkedHashMap<>();
        violations.put("balance differs from the ledger", inconsistentWallets());
        violations.put("balance_after is not the running sum", count("""
                SELECT count(*) FROM (SELECT balance_after, sum(amount) OVER (PARTITION BY wallet_id ORDER BY id) AS running
                                      FROM wallet_transactions) e WHERE e.balance_after <> e.running"""));
        violations.put("value out of range", count("""
                SELECT (SELECT count(*) FROM wallets WHERE balance NOT BETWEEN 0 AND 9007199254740991)
                     + (SELECT count(*) FROM wallet_transactions
                        WHERE balance_after NOT BETWEEN 0 AND 9007199254740991 OR amount = 0)"""));
        violations.put("several wallets per user", count(
                "SELECT count(*) FROM (SELECT user_id FROM wallets GROUP BY user_id HAVING count(*) > 1) d"));
        violations.put("wallet of a non-player", count(
                "SELECT count(*) FROM wallets w JOIN users u ON u.id = w.user_id WHERE u.role <> 'PLAYER'"));
        violations.put("key used twice in a wallet", count("""
                SELECT count(*) FROM (SELECT wallet_id, idempotency_key FROM wallet_transactions
                                      GROUP BY wallet_id, idempotency_key HAVING count(*) > 1) d"""));
        return violations;
    }

    /**
     * Wallets whose first entry is not the one {@code openWallet} writes ({@code STARTING_BALANCE}, key
     * {@code starting-balance}) or that have another {@code STARTING_BALANCE} entry; 0 when every wallet was
     * opened by registration or V7.
     */
    public long walletsNotOpenedByTheStartingEntry() {
        return count("""
                SELECT count(*) FROM wallets w
                WHERE (SELECT t.type || '/' || t.idempotency_key FROM wallet_transactions t WHERE t.wallet_id = w.id
                       ORDER BY t.id LIMIT 1) IS DISTINCT FROM 'STARTING_BALANCE/starting-balance'
                   OR (SELECT count(*) FROM wallet_transactions t WHERE t.wallet_id = w.id AND t.type = 'STARTING_BALANCE') <> 1""");
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
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
