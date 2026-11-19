package com.casino.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.casino.Concurrency;
import com.casino.Concurrency.Outcome;
import com.casino.ReadOnlyScripts;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.game.model.PlayedRound;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.RouletteWager;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.config.WalletProperties;
import com.casino.wallet.exception.InsufficientFundsException;
import com.casino.wallet.repository.WalletRepository;
import com.casino.wallet.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrent spins on PostgreSQL (W4-08, AC-W4-08.1–08.3; {@code docs/plans/week-04.md} §8.2, §8.4) with a
 * deterministic wheel and the production lock timeout ({@code app.wallet.lock-timeout}, 5 s). All threads of a
 * scenario start at the same moment ({@link Concurrency}); there are more threads than pooled connections
 * (10), so they also queue for connections, as under load.
 *
 * <p>No failure is tolerated silently: every outcome is either the expected result or the one expected
 * exception; anything else (a lock timeout, a unique-key violation) fails the test with its cause. After
 * every test the rounds are reconciled with the ledger by {@code infrastructure/neon/verify-roulette.sql} and
 * the wallets by {@code verify-wallets.sql} (both read-only), and the Week 3 ledger invariants are checked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RouletteConcurrencyIT {

    private static final int WINNING_NUMBER = 17; // black, odd, low (1–18), second dozen

    @Autowired
    private RouletteService roulette;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WalletProperties walletProperties;

    @MockitoBean
    private RouletteWheel wheel;

    private WalletTestData data;

    @BeforeEach
    void setUp() {
        data = new WalletTestData(jdbcTemplate, wallets, walletService, new TransactionTemplate(transactionManager));
        // The harness relies on the production wait: a spin that waits for the wallet must not time out.
        assertThat(walletProperties.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @AfterEach
    void reconcileAndReset() throws SQLException {
        try {
            try (Connection connection = dataSource.getConnection()) {
                List<String> roulette = ReadOnlyScripts.run(connection, "verify-roulette.sql");
                assertThat(roulette.get(0)).as("verify-roulette.sql violation counters").isEqualTo("0,0,0,0,0,0,0,0,0,0,0");
                assertThat(ReadOnlyScripts.run(connection, "verify-wallets.sql").get(0)).as("verify-wallets.sql").isEqualTo("0,0,0,0");
            }
            assertThat(data.ledgerViolations()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
        } finally {
            testDatabase.reset();
        }
    }

    // ------------------------------------------------------------------ A: competing spins of one player

    @Test
    void twentyCompetingSpinsOfOnePlayerSpendExactlyTheBalance() throws InterruptedException {
        // 20 losing spins of 100 coins, each with its own key, on a wallet of 1,000: exactly 10 fit.
        UUID player = data.openedPlayer("alice@example.com");
        when(wheel.spin()).thenReturn(WINNING_NUMBER);

        List<Outcome<PlayedRound>> outcomes = Concurrency.outcomes(20,
                () -> roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(0), 100))));

        List<PlayedRound> played = new ArrayList<>();
        int insufficient = 0;
        for (Outcome<PlayedRound> outcome : outcomes) {
            if (outcome.failure() == null) {
                played.add(outcome.result());
            } else if (outcome.failure() instanceof InsufficientFundsException) {
                insufficient++;
            } else {
                throw new AssertionError("Unexpected failure of a concurrent spin", outcome.failure());
            }
        }
        assertThat(played).hasSize(10);
        assertThat(insufficient).isEqualTo(10);
        // Serialized by the wallet lock: each committed spin saw the balance left by the one before.
        assertThat(played).extracting(PlayedRound::balance).containsExactlyInAnyOrder(900L, 800L, 700L, 600L, 500L, 400L, 300L, 200L, 100L, 0L);
        assertThat(played).extracting(PlayedRound::roundId).doesNotHaveDuplicates();
        // A rejected spin draws no number.
        verify(wheel, times(10)).spin();
        assertThat(data.balance(player)).isZero();
        assertThat(count("SELECT count(*) FROM roulette_rounds WHERE user_id = ?", player)).isEqualTo(10);
        assertThat(entries(player, "ROULETTE_STAKE")).isEqualTo(10);
        assertThat(entries(player, "ROULETTE_PAYOUT")).isZero();
        assertThat(sumOfStakes(player)).isEqualTo(-1000);
    }

    // ------------------------------------------------------------------ B: the same request in parallel

    @Test
    void tenParallelCopiesOfOneRequestPlayOneRound() throws InterruptedException {
        UUID player = data.openedPlayer("bob@example.com");
        UUID request = UUID.randomUUID();
        when(wheel.spin()).thenReturn(WINNING_NUMBER);
        List<RouletteWager> bets = List.of(new RouletteWager(new Straight(WINNING_NUMBER), 10),
                new RouletteWager(new RedBlack(RouletteBetColor.RED), 20));

        List<PlayedRound> results = Concurrency.runTogether(10, () -> roulette.spin(player, request, bets));

        // The first to get the lock plays; the others wait for it and find its committed round (§8.2).
        assertThat(new HashSet<>(results)).hasSize(1);
        PlayedRound round = results.getFirst();
        assertThat(round.winningNumber()).isEqualTo(WINNING_NUMBER);
        assertThat(round.totalStake()).isEqualTo(30);
        assertThat(round.totalPayout()).isEqualTo(360);
        assertThat(round.balance()).isEqualTo(1000 - 30 + 360);
        verify(wheel, times(1)).spin();
        assertThat(count("SELECT count(*) FROM roulette_rounds WHERE user_id = ?", player)).isOne();
        assertThat(count("SELECT count(*) FROM roulette_bets")).isEqualTo(2);
        assertThat(entries(player, "ROULETTE_STAKE")).isOne();
        assertThat(entries(player, "ROULETTE_PAYOUT")).isOne();
        assertThat(data.balance(player)).isEqualTo(1330);
    }

    // ------------------------------------------------------------------ C: two players

    @Test
    void mixedSpinsOfTwoPlayersInParallelEndWithTheExactBalances() throws InterruptedException {
        // The wheel always shows 17, so each spin's result does not depend on the order in which the spins
        // run; the stakes before any payout never exceed 1,000, so every spin is affordable in any order.
        UUID alice = data.openedPlayer("alice@example.com");
        UUID bob = data.openedPlayer("bob@example.com");
        when(wheel.spin()).thenReturn(WINNING_NUMBER);
        List<Spin> spins = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // Alice: red loses 50; a straight 17 (10 → 360) with black (20 → 40) wins 400 for 30.
            spins.add(new Spin(alice, List.of(new RouletteWager(new RedBlack(RouletteBetColor.RED), 50))));
            spins.add(new Spin(alice, List.of(new RouletteWager(new Straight(WINNING_NUMBER), 10),
                    new RouletteWager(new RedBlack(RouletteBetColor.BLACK), 20))));
            // Bob: the first dozen loses 100; odd wins 50 for 25.
            spins.add(new Spin(bob, List.of(new RouletteWager(new Dozen(RouletteDozen.FIRST), 100))));
            spins.add(new Spin(bob, List.of(new RouletteWager(new EvenOdd(RouletteParity.ODD), 25))));
        }
        AtomicInteger next = new AtomicInteger();

        List<PlayedRound> results = Concurrency.runTogether(spins.size(), () -> {
            Spin spin = spins.get(next.getAndIncrement());
            return roulette.spin(spin.player(), UUID.randomUUID(), spin.wagers());
        });

        assertThat(results).hasSize(20);
        assertThat(data.balance(alice)).isEqualTo(1000 - 5 * 50 - 5 * 30 + 5 * 400).isEqualTo(2600);
        assertThat(data.balance(bob)).isEqualTo(1000 - 5 * 100 - 5 * 25 + 5 * 50).isEqualTo(625);
        assertThat(entries(alice, "ROULETTE_STAKE")).isEqualTo(10);
        assertThat(entries(alice, "ROULETTE_PAYOUT")).isEqualTo(5);
        assertThat(entries(bob, "ROULETTE_STAKE")).isEqualTo(10);
        assertThat(entries(bob, "ROULETTE_PAYOUT")).isEqualTo(5);
        // Every round's entries are in its own player's wallet (also counted by verify-roulette.sql).
        assertThat(count("""
                SELECT count(*) FROM roulette_rounds r
                JOIN wallet_transactions t ON t.id IN (r.stake_transaction_id, r.payout_transaction_id)
                JOIN wallets w ON w.id = t.wallet_id
                WHERE w.user_id <> r.user_id""")).isZero();
        assertThat(count("SELECT count(*) FROM roulette_rounds WHERE user_id = ?", alice)).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM roulette_rounds WHERE user_id = ?", bob)).isEqualTo(10);
    }

    @Test
    void aSpinOfOnePlayerDoesNotWaitForAnotherPlayersLockedWallet() throws Exception {
        // Alice's spin is held inside the draw, i.e. while her wallet row is locked. Bob's spin must complete
        // meanwhile; if the two shared a lock, Bob's spin would wait and fail after the 5 s lock timeout.
        UUID alice = data.openedPlayer("alice@example.com");
        UUID bob = data.openedPlayer("bob@example.com");
        CountDownLatch aliceDrawing = new CountDownLatch(1);
        CountDownLatch bobDone = new CountDownLatch(1);
        AtomicInteger draws = new AtomicInteger();
        when(wheel.spin()).thenAnswer(invocation -> {
            if (draws.getAndIncrement() == 0) {
                aliceDrawing.countDown();
                assertThat(bobDone.await(30, TimeUnit.SECONDS)).as("Bob's spin finished while Alice's wallet was locked").isTrue();
            }
            return WINNING_NUMBER;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PlayedRound> aliceSpin = executor.submit(
                    () -> roulette.spin(alice, UUID.randomUUID(), List.of(new RouletteWager(new Straight(WINNING_NUMBER), 10))));
            assertThat(aliceDrawing.await(30, TimeUnit.SECONDS)).isTrue();

            // Lock state from separate connections: Alice's wallet is locked, Bob's is not.
            assertThat(tryLock(alice)).isEqualTo("55P03"); // lock_not_available
            assertThat(tryLock(bob)).isEqualTo("not locked");
            PlayedRound bobs = roulette.spin(bob, UUID.randomUUID(), List.of(new RouletteWager(new RedBlack(RouletteBetColor.RED), 100)));
            assertThat(tryLock(alice)).isEqualTo("55P03"); // Alice's spin is still in its draw
            bobDone.countDown();
            PlayedRound alices = aliceSpin.get(30, TimeUnit.SECONDS);

            assertThat(bobs.balance()).isEqualTo(900);
            assertThat(alices.balance()).isEqualTo(1350);
            // Interleaved: Alice debited first, but Bob's round was stored first.
            assertThat(jdbcTemplate.queryForList("SELECT r.user_id FROM roulette_rounds r JOIN wallet_transactions t "
                    + "ON t.id = r.stake_transaction_id ORDER BY t.id", UUID.class)).containsExactly(alice, bob);
            assertThat(jdbcTemplate.queryForList("SELECT user_id FROM roulette_rounds ORDER BY created_at", UUID.class))
                    .containsExactly(bob, alice);
        } finally {
            bobDone.countDown();
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Spin(UUID player, List<RouletteWager> wagers) {
    }

    /** {@code FOR UPDATE NOWAIT} on the player's wallet from a separate connection: its SQLSTATE, or "not locked". */
    private String tryLock(UUID player) {
        try (Connection other = dataSource.getConnection();
             PreparedStatement lock = other.prepareStatement("SELECT 1 FROM wallets WHERE user_id = ? FOR UPDATE NOWAIT")) {
            lock.setObject(1, player);
            lock.executeQuery().close();
            return "not locked";
        } catch (SQLException ex) {
            return ex.getSQLState();
        }
    }

    private long entries(UUID player, String type) {
        return count("SELECT count(*) FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ? AND t.type = '"
                + type + "'", player);
    }

    private long sumOfStakes(UUID player) {
        return count("SELECT coalesce(sum(t.amount), 0) FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id "
                + "WHERE w.user_id = ? AND t.type = 'ROULETTE_STAKE'", player);
    }

    private long count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }
}
