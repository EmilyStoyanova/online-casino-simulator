package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.exception.WalletBusyException;
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
 * The bounded lock wait (AC-7.5, Q11), verified against PostgreSQL rather than assumed: Hibernate turns
 * the lock-timeout hint into {@code SET LOCAL lock_timeout} for the locking statement and restores the
 * previous value afterwards. A wait beyond {@code app.wallet.lock-timeout} fails as
 * {@link WalletBusyException} ({@code 503}) and changes nothing.
 */
@SpringBootTest(properties = "app.wallet.lock-timeout=500ms")
@Import(TestcontainersConfiguration.class)
class WalletLockTimeoutIT {

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
    void aWalletLockedForLongerThanTheTimeoutFailsTheOperationWithoutChanges() throws Exception {
        UUID player = data.playerWithBalance("alice@example.com", 1000);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // Another transaction holds the wallet's row lock (as a slow operation would).
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE user_id = ? FOR UPDATE", Long.class, player);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> walletService.debit(player, debit(100, "blocked")))
                    .isInstanceOf(WalletBusyException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            // Bounded: at least the timeout, far less than an unbounded wait (the holder waits up to 10 s).
            assertThat(waited).isBetween(Duration.ofMillis(450), Duration.ofSeconds(4));
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(data.balance(player)).isEqualTo(1000);
        assertThat(data.entries(player)).isOne();
        // Once the lock is free, the same operation (same key) succeeds: the failed one left nothing.
        assertThat(walletService.debit(player, debit(100, "blocked")).balanceAfter()).isEqualTo(900);
    }

    @Test
    void theLockTimeoutAppliesOnlyToTheLockingStatement() {
        UUID player = data.playerWithBalance("bob@example.com", 1000);

        String afterLock = transactions.execute(status -> {
            walletService.debit(player, debit(1, "restore-check"));
            // Later statements of the caller's transaction (e.g. a Week 4 round) keep the server default.
            return jdbcTemplate.queryForObject("SELECT current_setting('lock_timeout')", String.class);
        });

        assertThat(afterLock).isEqualTo("0");
    }

    private static WalletOperation debit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_DEBIT, amount, key, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not release the lock");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
