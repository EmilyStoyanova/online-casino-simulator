package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.casino.Concurrency;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.entity.Wallet;
import com.casino.wallet.exception.BalanceLimitExceededException;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
import com.casino.wallet.exception.InsufficientFundsException;
import com.casino.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The wallet and ledger invariants under a long, mixed workload (W3-09, AC-8.x): every successful
 * change has exactly one entry, balance = Σ entries = newest {@code balance_after}, each
 * {@code balance_after} is the running sum, values stay within 0 … 2^53 - 1, failures and rolled-back
 * caller transactions leave nothing, and repeated keys never apply twice.
 *
 * <p>Deterministic: the workloads come from fixed seeds. The sequential run is compared with an
 * in-memory model after every operation; the concurrent run, whose interleaving varies, is checked
 * through order-independent invariants only.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerIntegrityIT {

    private static final int PLAYERS = 5;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private WalletTestData data;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        data = new WalletTestData(jdbcTemplate, wallets, walletService, transactions);
    }

    @AfterEach
    void resetDatabase() {
        testDatabase.reset();
    }

    @Test
    void aLongMixedWorkloadMatchesTheModelAfterEveryOperation() {
        List<UUID> players = openPlayers();
        Map<UUID, Long> balance = new HashMap<>();
        Map<UUID, Long> entries = new HashMap<>();
        Map<UUID, Map<String, long[]>> applied = new HashMap<>();
        players.forEach(player -> {
            balance.put(player, 1000L);
            entries.put(player, 1L);
            applied.put(player, new HashMap<>());
        });
        Random random = new Random(20_261_110L);
        int[] outcomes = new int[6];

        for (int i = 0; i < 600; i++) {
            int n = i;
            UUID player = players.get(random.nextInt(PLAYERS));
            Map<String, long[]> keys = applied.get(player);
            long current = balance.get(player);
            int kind = random.nextInt(6);
            outcomes[kind]++;
            switch (kind) {
                case 0 -> { // credit with a new key
                    long amount = 1 + random.nextInt(500);
                    WalletOperationResult result = walletService.credit(player, credit(amount, "c-" + n));
                    expectApplied(result, current + amount);
                    keys.put("c-" + n, new long[] {amount, result.balanceAfter(), result.transactionId()});
                    balance.put(player, current + amount);
                    entries.merge(player, 1L, Long::sum);
                }
                case 1 -> { // debit with a new key, rejected when the balance is too low
                    long amount = 1 + random.nextInt(800);
                    if (amount > current) {
                        assertThatThrownBy(() -> walletService.debit(player, debit(amount, "d-" + n)))
                                .isInstanceOf(InsufficientFundsException.class);
                    } else {
                        WalletOperationResult result = walletService.debit(player, debit(amount, "d-" + n));
                        expectApplied(result, current - amount);
                        keys.put("d-" + n, new long[] {-amount, result.balanceAfter(), result.transactionId()});
                        balance.put(player, current - amount);
                        entries.merge(player, 1L, Long::sum);
                    }
                }
                case 2 -> { // an earlier operation repeated: its original result, nothing applied
                    if (!keys.isEmpty()) {
                        String key = pick(keys, random);
                        long[] original = keys.get(key);
                        WalletOperation again = original[0] > 0 ? credit(original[0], key) : debit(-original[0], key);
                        WalletOperationResult result = original[0] > 0 ? walletService.credit(player, again)
                                : walletService.debit(player, again);
                        assertThat(result).isEqualTo(new WalletOperationResult(original[2], original[1], true));
                    }
                }
                case 3 -> { // an earlier key with other values: rejected
                    if (!keys.isEmpty()) {
                        String key = pick(keys, random);
                        long[] original = keys.get(key);
                        assertThatThrownBy(() -> walletService.credit(player, credit(Math.abs(original[0]) + 1, key)))
                                .isInstanceOf(IdempotencyKeyReusedException.class);
                    }
                }
                case 4 -> { // applied inside a caller transaction that then fails: nothing remains
                    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                        walletService.credit(player, credit(10, "r-" + n));
                        throw new IllegalStateException("caller failed");
                    })).hasMessage("caller failed");
                }
                default -> // beyond 2^53 - 1: rejected
                        assertThatThrownBy(() -> walletService.credit(player, credit(Wallet.MAX_BALANCE, "m-" + n)))
                                .isInstanceOf(BalanceLimitExceededException.class);
            }
            assertThat(data.balance(player)).as("operation %d", n).isEqualTo(balance.get(player));
        }

        // Every kind of operation occurred.
        assertThat(outcomes).doesNotContain(0);
        for (UUID player : players) {
            assertThat(data.balance(player)).isEqualTo(balance.get(player));
            assertThat(data.entries(player)).isEqualTo(entries.get(player));
        }
        assertThat(data.ledgerViolations()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
        assertThat(data.walletsNotOpenedByTheStartingEntry()).isZero();
        // Rolled-back keys left nothing behind.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transactions WHERE idempotency_key LIKE 'r-%'",
                Long.class)).isZero();
    }

    @Test
    void aConcurrentMixedWorkloadKeepsEveryInvariant() throws InterruptedException {
        List<UUID> players = openPlayers();
        AtomicInteger thread = new AtomicInteger();
        AtomicInteger applied = new AtomicInteger();

        // 16 threads (more than the 10 pooled connections) x 60 operations on 5 shared wallets.
        Concurrency.runTogether(16, () -> {
            int t = thread.getAndIncrement();
            Random random = new Random(1_000L + t);
            for (int i = 0; i < 60; i++) {
                int n = i;
                UUID player = players.get(random.nextInt(PLAYERS));
                long amount = 1 + random.nextInt(300);
                try {
                    WalletOperationResult result = switch (random.nextInt(4)) {
                        case 0 -> walletService.credit(player, credit(amount, "c-" + t + "-" + n));
                        case 1 -> walletService.debit(player, debit(amount, "d-" + t + "-" + n));
                        // The same operation from every thread: applied once, replayed otherwise.
                        case 2 -> walletService.credit(players.get(n % PLAYERS), credit(7, "shared-" + n));
                        default -> {
                            try {
                                transactions.executeWithoutResult(status -> {
                                    walletService.debit(player, debit(1, "r-" + t + "-" + n));
                                    throw new CallerFailure();
                                });
                            } catch (CallerFailure expected) {
                                // The caller transaction rolled back, with its debit.
                            }
                            yield null;
                        }
                    };
                    if (result != null && !result.replayed()) {
                        applied.incrementAndGet();
                    }
                } catch (InsufficientFundsException expected) {
                    // A debit larger than the balance at that moment.
                }
            }
            return null;
        });

        // Every successful change has exactly one entry: entries = starting entries + applied changes.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transactions", Long.class))
                .isEqualTo(PLAYERS + applied.get());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (SELECT idempotency_key FROM wallet_transactions WHERE idempotency_key LIKE 'shared-%'
                                      GROUP BY idempotency_key HAVING count(*) <> 1) d""", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transactions WHERE idempotency_key LIKE 'r-%'",
                Long.class)).isZero();
        assertThat(data.ledgerViolations()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
        assertThat(data.walletsNotOpenedByTheStartingEntry()).isZero();
    }

    private List<UUID> openPlayers() {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            players.add(data.openedPlayer("player" + i + "@example.com"));
        }
        return players;
    }

    private static void expectApplied(WalletOperationResult result, long balanceAfter) {
        assertThat(result.replayed()).isFalse();
        assertThat(result.balanceAfter()).isEqualTo(balanceAfter);
    }

    private static String pick(Map<String, long[]> keys, Random random) {
        List<String> sorted = keys.keySet().stream().sorted().toList();
        return sorted.get(random.nextInt(sorted.size()));
    }

    /** The deliberate failure of a caller transaction after a wallet change. */
    private static final class CallerFailure extends RuntimeException {
    }

    private static WalletOperation credit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_CREDIT, amount, key, null);
    }

    private static WalletOperation debit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_DEBIT, amount, key, null);
    }
}
