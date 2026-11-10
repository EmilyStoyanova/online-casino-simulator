package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.casino.Concurrency;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
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
 * Concurrent balance changes on PostgreSQL (AC-7.1, AC-7.2, AC-6.3): no overspending, no lost update,
 * no duplicate operation, and the ledger stays consistent. All threads start at the same moment
 * ({@link Concurrency}); there are more threads than database connections, so they also queue for
 * connections, as under load.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WalletConcurrencyIT {

    private static final int THREADS = 50;

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

    private WalletTestData data;

    @BeforeEach
    void setUp() {
        data = new WalletTestData(jdbcTemplate, wallets, walletService, new TransactionTemplate(transactionManager));
    }

    @AfterEach
    void resetDatabase() {
        testDatabase.reset();
    }

    @Test
    void concurrentDebitsNeverOverspend() throws InterruptedException {
        // 50 debits of 30 on 1,000 coins: exactly 33 fit (990), 17 are rejected, 10 coins stay.
        UUID player = data.playerWithBalance("alice@example.com", 1000);
        AtomicInteger key = new AtomicInteger();

        List<Concurrency.Outcome<WalletOperationResult>> outcomes = Concurrency.outcomes(THREADS,
                () -> walletService.debit(player, debit(30, "debit-" + key.incrementAndGet())));

        assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).hasSize(33);
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null)
                .hasSize(17)
                .allSatisfy(outcome -> assertThat(outcome.failure()).isInstanceOf(InsufficientFundsException.class));
        assertThat(data.balance(player)).isEqualTo(10);
        assertThat(data.entries(player)).isEqualTo(1 + 33);
        // Every successful debit saw its own, distinct balance.
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null)
                .extracting(outcome -> outcome.result().balanceAfter())
                .doesNotHaveDuplicates()
                .allSatisfy(balance -> assertThat(balance).isBetween(10L, 970L));
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void concurrentDuplicatesOfOneCreditApplyItOnce() throws InterruptedException {
        UUID player = data.playerWithBalance("bob@example.com", 0);

        List<WalletOperationResult> results = Concurrency.runTogether(THREADS,
                () -> walletService.credit(player, new WalletOperation(WalletTransactionType.TEST_CREDIT, 1000, "starting-coins", null)));

        assertThat(results).extracting(WalletOperationResult::transactionId).containsOnly(results.getFirst().transactionId());
        assertThat(results).extracting(WalletOperationResult::balanceAfter).containsOnly(1000L);
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(data.balance(player)).isEqualTo(1000);
        assertThat(data.entries(player)).isOne();
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void concurrentDuplicatesOfOneDebitApplyItOnce() throws InterruptedException {
        UUID player = data.playerWithBalance("carol@example.com", 1000);

        List<WalletOperationResult> results = Concurrency.runTogether(THREADS,
                () -> walletService.debit(player, debit(600, "round-3:stake")));

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(WalletOperationResult::balanceAfter).containsOnly(400L);
        assertThat(data.balance(player)).isEqualTo(400);
        assertThat(data.entries(player)).isEqualTo(2);
    }

    @Test
    void concurrentCreditsAndDebitsLoseNoUpdate() throws InterruptedException {
        // 25 credits of 7 and 25 debits of 5 on 1,000 coins: every debit fits whatever the order.
        UUID player = data.playerWithBalance("dave@example.com", 1000);
        AtomicInteger counter = new AtomicInteger();

        Concurrency.runTogether(THREADS, () -> {
            int n = counter.incrementAndGet();
            return n % 2 == 0
                    ? walletService.credit(player, new WalletOperation(WalletTransactionType.TEST_CREDIT, 7, "credit-" + n, null))
                    : walletService.debit(player, debit(5, "debit-" + n));
        });

        assertThat(data.balance(player)).isEqualTo(1000 + 25 * 7 - 25 * 5);
        assertThat(data.entries(player)).isEqualTo(1 + THREADS);
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void operationsOnDifferentWalletsDoNotWaitForEachOther() throws InterruptedException {
        // One debit per wallet in parallel: no shared lock, so all succeed.
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            players.add(data.playerWithBalance("player" + i + "@example.com", 100));
        }
        AtomicInteger index = new AtomicInteger();

        List<WalletOperationResult> results = Concurrency.runTogether(players.size(),
                () -> walletService.debit(players.get(index.getAndIncrement()), debit(100, "all-in")));

        assertThat(results).extracting(WalletOperationResult::balanceAfter).containsOnly(0L);
        assertThat(players).allSatisfy(player -> assertThat(data.balance(player)).isZero());
        assertThat(data.inconsistentWallets()).isZero();
    }

    private static WalletOperation debit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_DEBIT, amount, key, null);
    }
}
