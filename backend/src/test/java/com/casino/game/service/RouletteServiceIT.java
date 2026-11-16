package com.casino.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.game.model.PlayedRound;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteNumberColor;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteRange;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.RouletteWager;
import com.casino.game.model.SettledWager;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.entity.Wallet;
import com.casino.wallet.exception.BalanceLimitExceededException;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
import com.casino.wallet.exception.InsufficientFundsException;
import com.casino.wallet.exception.WalletBusyException;
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
 * {@link RouletteService#spin} against PostgreSQL (W4-06, AC-W4-06.1–06.6) with a deterministic wheel. After
 * every test the stored rounds are reconciled with the ledger ({@link #roundLedgerViolations()}): each
 * referenced entry belongs to the round's player, has the right type, signed amount, key and reference,
 * and no roulette entry exists without its round. The larger concurrency and reconciliation suites follow
 * in W4-08.
 */
@SpringBootTest(properties = "app.wallet.lock-timeout=500ms")
@Import(TestcontainersConfiguration.class)
class RouletteServiceIT {

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

    @MockitoBean
    private RouletteWheel wheel;

    private TransactionTemplate transactions;
    private WalletTestData data;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        data = new WalletTestData(jdbcTemplate, wallets, walletService, transactions);
    }

    @AfterEach
    void reconcileAndReset() {
        try {
            assertThat(roundLedgerViolations()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
            assertThat(data.ledgerViolations()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
        } finally {
            testDatabase.reset();
        }
    }

    // ------------------------------------------------------------------ rounds

    @Test
    void aWinningStraightBetDebitsTheStakeAndCreditsThirtySixTimesIt() {
        UUID player = data.openedPlayer("alice@example.com");
        when(wheel.spin()).thenReturn(17);

        PlayedRound round = roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(17), 10)));

        assertThat(round.winningNumber()).isEqualTo(17);
        assertThat(round.winningColor()).isEqualTo(RouletteNumberColor.BLACK);
        assertThat(round.bets()).containsExactly(new SettledWager(new Straight(17), 10, 360));
        assertThat(round.totalStake()).isEqualTo(10);
        assertThat(round.totalPayout()).isEqualTo(360);
        assertThat(round.balance()).isEqualTo(1000 - 10 + 360).isEqualTo(data.balance(player));
        UUID id = round.roundId();
        assertThat(rouletteEntries(player)).containsExactly(
                "ROULETTE_STAKE -10 -> 990 key roulette-round:" + id + ":stake ref " + id,
                "ROULETTE_PAYOUT 360 -> 1350 key roulette-round:" + id + ":payout ref " + id);
        assertThat(counts()).isEqualTo("rounds 1, bets 1");
    }

    @Test
    void aLosingStraightBetHasOnlyItsStakeEntry() {
        UUID player = data.openedPlayer("bob@example.com");
        when(wheel.spin()).thenReturn(5);

        PlayedRound round = roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(17), 10)));

        assertThat(round.totalPayout()).isZero();
        assertThat(round.balance()).isEqualTo(990).isEqualTo(data.balance(player));
        assertThat(rouletteEntries(player)).containsExactly("ROULETTE_STAKE -10 -> 990 key roulette-round:" + round.roundId()
                + ":stake ref " + round.roundId());
        assertThat(jdbcTemplate.queryForObject("SELECT payout_transaction_id IS NULL FROM roulette_rounds", Boolean.class)).isTrue();
    }

    @Test
    void redWinsOnARedNumberAndLosesOnABlackOne() {
        UUID player = data.openedPlayer("carol@example.com");
        RouletteWager red = new RouletteWager(new RedBlack(RouletteBetColor.RED), 20);

        when(wheel.spin()).thenReturn(1);
        PlayedRound win = roulette.spin(player, UUID.randomUUID(), List.of(red));
        when(wheel.spin()).thenReturn(2);
        PlayedRound loss = roulette.spin(player, UUID.randomUUID(), List.of(red));

        assertThat(win.winningColor()).isEqualTo(RouletteNumberColor.RED);
        assertThat(win.totalPayout()).isEqualTo(40);
        assertThat(win.balance()).isEqualTo(1020);
        assertThat(loss.winningColor()).isEqualTo(RouletteNumberColor.BLACK);
        assertThat(loss.totalPayout()).isZero();
        assertThat(loss.balance()).isEqualTo(1000).isEqualTo(data.balance(player));
        assertThat(counts()).isEqualTo("rounds 2, bets 2");
    }

    @Test
    void zeroPaysOnlyTheStraightBetOnZero() {
        UUID player = data.openedPlayer("dave@example.com");
        when(wheel.spin()).thenReturn(0);

        PlayedRound round = roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(0), 5),
                new RouletteWager(new EvenOdd(RouletteParity.EVEN), 10), new RouletteWager(new RedBlack(RouletteBetColor.RED), 10),
                new RouletteWager(new LowHigh(RouletteRange.LOW), 10), new RouletteWager(new Dozen(RouletteDozen.FIRST), 10)));

        assertThat(round.winningColor()).isEqualTo(RouletteNumberColor.GREEN);
        assertThat(round.bets()).extracting(SettledWager::payout).containsExactly(180L, 0L, 0L, 0L, 0L);
        assertThat(round.totalStake()).isEqualTo(45);
        assertThat(round.totalPayout()).isEqualTo(180);
        assertThat(round.balance()).isEqualTo(1000 - 45 + 180).isEqualTo(data.balance(player));
    }

    @Test
    void severalWagersAreSettledTogetherAndStoredInRequestOrder() {
        UUID player = data.openedPlayer("erin@example.com");
        when(wheel.spin()).thenReturn(17);
        List<RouletteWager> wagers = List.of(new RouletteWager(new Dozen(RouletteDozen.SECOND), 15),
                new RouletteWager(new Straight(17), 10), new RouletteWager(new RedBlack(RouletteBetColor.RED), 20),
                new RouletteWager(new EvenOdd(RouletteParity.ODD), 5), new RouletteWager(new LowHigh(RouletteRange.HIGH), 5));

        PlayedRound round = roulette.spin(player, UUID.randomUUID(), wagers);

        assertThat(round.bets()).extracting(SettledWager::selection)
                .containsExactlyElementsOf(wagers.stream().map(RouletteWager::selection).toList());
        assertThat(round.bets()).extracting(SettledWager::payout).containsExactly(45L, 360L, 0L, 10L, 0L);
        assertThat(round.totalStake()).isEqualTo(55);
        assertThat(round.totalPayout()).isEqualTo(415);
        // finalBalance = initialBalance − totalStake + totalPayout
        assertThat(data.balance(player)).isEqualTo(1000 - 55 + 415).isEqualTo(round.balance());
        assertThat(jdbcTemplate.queryForList("SELECT category || ':' || coalesce(selection, number::text) FROM roulette_bets ORDER BY id",
                String.class)).containsExactly("DOZEN:SECOND", "STRAIGHT:17", "RED_BLACK:RED", "EVEN_ODD:ODD", "LOW_HIGH:HIGH");
    }

    // ------------------------------------------------------------------ idempotency (Q17)

    @Test
    void theSameRequestReturnsTheOriginalRoundWithoutTouchingTheWallet() {
        UUID player = data.openedPlayer("frank@example.com");
        UUID request = UUID.randomUUID();
        List<RouletteWager> wagers = List.of(new RouletteWager(new Straight(17), 10), new RouletteWager(new RedBlack(RouletteBetColor.RED), 20));
        when(wheel.spin()).thenReturn(17);
        PlayedRound original = roulette.spin(player, request, wagers);
        String ledger = ledger();

        PlayedRound replay = roulette.spin(player, request, wagers);
        // Q17: another order of the same bets is the same content; the original order is returned.
        PlayedRound reordered = roulette.spin(player, request, List.of(wagers.get(1), wagers.get(0)));

        assertThat(replay).isEqualTo(original);
        assertThat(reordered).isEqualTo(original);
        assertThat(reordered.bets()).extracting(SettledWager::selection).containsExactly(new Straight(17), new RedBlack(RouletteBetColor.RED));
        // Microsecond precision is preserved: the stored timestamp is the returned one.
        assertThat(original.createdAt().getNano() % 1000).isZero();
        verify(wheel, times(1)).spin();
        assertThat(ledger()).isEqualTo(ledger);
        assertThat(counts()).isEqualTo("rounds 1, bets 2");
    }

    @Test
    void aReplayReturnsTheOriginalBalanceEvenAfterTheWalletHasChanged() {
        UUID player = data.openedPlayer("grace@example.com");
        UUID request = UUID.randomUUID();
        List<RouletteWager> wagers = List.of(new RouletteWager(new Dozen(RouletteDozen.THIRD), 50));
        when(wheel.spin()).thenReturn(8);
        PlayedRound original = roulette.spin(player, request, wagers);
        roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(8), 100))); // a later round, 8 again
        assertThat(data.balance(player)).isEqualTo(950 - 100 + 3600);

        PlayedRound replay = roulette.spin(player, request, wagers);

        assertThat(replay).isEqualTo(original);
        assertThat(replay.balance()).isEqualTo(950);
        assertThat(data.balance(player)).isEqualTo(4450);
    }

    @Test
    void theSameRequestIdWithOtherBetsIsAConflictAndChangesNothing() {
        UUID player = data.openedPlayer("heidi@example.com");
        UUID request = UUID.randomUUID();
        when(wheel.spin()).thenReturn(17);
        roulette.spin(player, request, List.of(new RouletteWager(new Straight(17), 10)));
        String ledger = ledger();

        assertThatThrownBy(() -> roulette.spin(player, request, List.of(new RouletteWager(new Straight(17), 20))))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> roulette.spin(player, request, List.of(new RouletteWager(new Straight(18), 10))))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verify(wheel, times(1)).spin();
        assertThat(ledger()).isEqualTo(ledger);
        assertThat(counts()).isEqualTo("rounds 1, bets 1");
    }

    @Test
    void anotherPlayerMayUseTheSameRequestId() {
        UUID alice = data.openedPlayer("alice@example.com");
        UUID bob = data.openedPlayer("bob@example.com");
        UUID request = UUID.randomUUID();
        when(wheel.spin()).thenReturn(17, 4);

        PlayedRound alices = roulette.spin(alice, request, List.of(new RouletteWager(new Straight(17), 10)));
        PlayedRound bobs = roulette.spin(bob, request, List.of(new RouletteWager(new Straight(17), 10)));

        assertThat(bobs.roundId()).isNotEqualTo(alices.roundId());
        assertThat(alices.balance()).isEqualTo(1350).isEqualTo(data.balance(alice));
        assertThat(bobs.balance()).isEqualTo(990).isEqualTo(data.balance(bob));
        assertThat(counts()).isEqualTo("rounds 2, bets 2");
    }

    // ------------------------------------------------------------------ failures: nothing is committed

    @Test
    void insufficientFundsStopsBeforeTheWheelAndWritesNothing() {
        UUID player = data.playerWithBalance("ivan@example.com", 50);
        String ledger = ledger();

        assertThatThrownBy(() -> roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(1), 100))))
                .isInstanceOf(InsufficientFundsException.class);

        verify(wheel, never()).spin();
        assertNothingChanged(ledger);
    }

    @Test
    void invalidWagersWriteNothing() {
        UUID player = data.openedPlayer("judy@example.com");
        String ledger = ledger();
        RouletteWager red = new RouletteWager(new RedBlack(RouletteBetColor.RED), 10);

        assertThatIllegalArgumentException().isThrownBy(() -> roulette.spin(player, UUID.randomUUID(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> roulette.spin(player, UUID.randomUUID(),
                List.of(new RouletteWager(new Straight(3), 1001))));
        assertThatIllegalArgumentException().isThrownBy(() -> roulette.spin(player, UUID.randomUUID(), List.of(red, red)));

        verify(wheel, never()).spin();
        assertNothingChanged(ledger);
    }

    @Test
    void aFailureAfterTheDebitRollsTheStakeBack() {
        UUID player = data.openedPlayer("kim@example.com");
        UUID request = UUID.randomUUID();
        String ledger = ledger();
        when(wheel.spin()).thenThrow(new IllegalStateException("wheel failure"));

        assertThatThrownBy(() -> roulette.spin(player, request, List.of(new RouletteWager(new Straight(1), 10))))
                .hasMessage("wheel failure");
        assertNothingChanged(ledger);

        // Nothing was recorded, so the same request id plays the round afterwards.
        doReturn(1).when(wheel).spin();
        assertThat(roulette.spin(player, request, List.of(new RouletteWager(new Straight(1), 10))).balance()).isEqualTo(1350);
    }

    @Test
    void aFailingPayoutRollsTheStakeBack() {
        // The payout would take the balance beyond 2^53 − 1: BALANCE_LIMIT_EXCEEDED, after the debit.
        UUID player = data.playerWithBalance("liam@example.com", Wallet.MAX_BALANCE - 100);
        String ledger = ledger();
        when(wheel.spin()).thenReturn(17);

        assertThatThrownBy(() -> roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(17), 10))))
                .isInstanceOf(BalanceLimitExceededException.class);

        assertNothingChanged(ledger);
        assertThat(data.balance(player)).isEqualTo(Wallet.MAX_BALANCE - 100);
    }

    @Test
    void aFailingRoundInsertAfterThePayoutRollsBothEntriesBack() {
        UUID player = data.openedPlayer("mia@example.com");
        UUID request = UUID.randomUUID();
        String ledger = ledger();
        when(wheel.spin()).thenReturn(17);

        withInjectedFailure("roulette_rounds", () -> assertThatThrownBy(() -> roulette.spin(player, request,
                List.of(new RouletteWager(new Straight(17), 10)))).hasStackTraceContaining("injected failure"));

        assertNothingChanged(ledger);
        assertThat(roulette.spin(player, request, List.of(new RouletteWager(new Straight(17), 10))).balance()).isEqualTo(1350);
    }

    @Test
    void aRejectedBetRollsTheWholeRoundBack() {
        UUID player = data.openedPlayer("noah@example.com");
        String ledger = ledger();
        when(wheel.spin()).thenReturn(17);

        withInjectedFailure("roulette_bets", () -> assertThatThrownBy(() -> roulette.spin(player, UUID.randomUUID(),
                List.of(new RouletteWager(new Straight(17), 10), new RouletteWager(new RedBlack(RouletteBetColor.BLACK), 10))))
                .hasStackTraceContaining("injected failure"));

        assertNothingChanged(ledger);
    }

    // ------------------------------------------------------------------ the wallet lock

    @Test
    void theWalletStaysLockedWhileTheNumberIsDrawn() {
        UUID player = data.openedPlayer("olga@example.com");
        List<String> observed = new ArrayList<>();
        when(wheel.spin()).thenAnswer(invocation -> {
            // From a separate connection, inside the spin's transaction: the wallet row must be locked.
            try (Connection other = dataSource.getConnection();
                 PreparedStatement lock = other.prepareStatement("SELECT 1 FROM wallets WHERE user_id = ? FOR UPDATE NOWAIT")) {
                lock.setObject(1, player);
                lock.executeQuery();
                observed.add("not locked");
            } catch (SQLException ex) {
                observed.add(ex.getSQLState());
            }
            return 17;
        });

        roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(17), 10)));

        assertThat(observed).containsExactly("55P03"); // lock_not_available
    }

    @Test
    void aWalletLockedLongerThanTheTimeoutFailsTheSpinWithoutChanges() throws Exception {
        UUID player = data.openedPlayer("pia@example.com");
        String ledger = ledger();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                walletService.lockWallet(player);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> roulette.spin(player, UUID.randomUUID(), List.of(new RouletteWager(new Straight(1), 10))))
                    .isInstanceOf(WalletBusyException.class);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        verify(wheel, never()).spin();
        assertNothingChanged(ledger);
    }

    @Test
    void lockWalletNeedsATransaction() {
        UUID player = data.openedPlayer("quinn@example.com");

        assertThatThrownBy(() -> walletService.lockWallet(player))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Reconciliation of rounds and ledger (§5.5, §5.7): what the database does not check, counted by name.
     * Every count must be 0.
     */
    private Map<String, Long> roundLedgerViolations() {
        Map<String, Long> violations = new LinkedHashMap<>();
        violations.put("stake entry not the round's own", count("""
                SELECT count(*) FROM roulette_rounds r JOIN wallet_transactions t ON t.id = r.stake_transaction_id
                JOIN wallets w ON w.id = t.wallet_id
                WHERE w.user_id <> r.user_id OR t.type <> 'ROULETTE_STAKE' OR t.amount <> -r.total_stake
                   OR t.reference IS DISTINCT FROM r.id::text OR t.idempotency_key <> 'roulette-round:' || r.id || ':stake'"""));
        violations.put("payout entry not the round's own", count("""
                SELECT count(*) FROM roulette_rounds r JOIN wallet_transactions t ON t.id = r.payout_transaction_id
                JOIN wallets w ON w.id = t.wallet_id
                WHERE w.user_id <> r.user_id OR t.type <> 'ROULETTE_PAYOUT' OR t.amount <> r.total_payout
                   OR t.reference IS DISTINCT FROM r.id::text OR t.idempotency_key <> 'roulette-round:' || r.id || ':payout'
                   OR t.id <= r.stake_transaction_id"""));
        violations.put("balance after not the last entry's", count("""
                SELECT count(*) FROM roulette_rounds r
                JOIN wallet_transactions t ON t.id = coalesce(r.payout_transaction_id, r.stake_transaction_id)
                WHERE t.balance_after <> r.balance_after"""));
        violations.put("totals not the sum of the bets", count("""
                SELECT count(*) FROM roulette_rounds r
                WHERE (SELECT count(*) FROM roulette_bets b WHERE b.round_id = r.id) NOT BETWEEN 1 AND 10
                   OR r.total_stake <> (SELECT sum(b.amount) FROM roulette_bets b WHERE b.round_id = r.id)
                   OR r.total_payout <> (SELECT sum(b.payout) FROM roulette_bets b WHERE b.round_id = r.id)"""));
        // WalletTestData.playerWithBalance funds a test wallet with one ROULETTE_PAYOUT credit keyed "funding"
        // (the only credit type besides the starting balance); it is a fixture, not a round, and is excluded.
        violations.put("roulette entry without its round", count("""
                SELECT count(*) FROM wallet_transactions t
                WHERE t.idempotency_key <> 'funding'
                  AND ((t.type = 'ROULETTE_STAKE' AND NOT EXISTS (SELECT 1 FROM roulette_rounds r WHERE r.stake_transaction_id = t.id))
                    OR (t.type = 'ROULETTE_PAYOUT' AND NOT EXISTS (SELECT 1 FROM roulette_rounds r WHERE r.payout_transaction_id = t.id)))"""));
        return violations;
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    /** The player's roulette ledger entries, oldest first. */
    private List<String> rouletteEntries(UUID player) {
        return jdbcTemplate.queryForList("""
                SELECT t.type || ' ' || t.amount || ' -> ' || t.balance_after || ' key ' || t.idempotency_key || ' ref ' || t.reference
                FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id
                WHERE w.user_id = ? AND t.type LIKE 'ROULETTE_%' ORDER BY t.id""", String.class, player);
    }

    /** Every wallet and ledger row, to prove that a failed spin changed nothing. */
    private String ledger() {
        return jdbcTemplate.queryForObject("SELECT coalesce(string_agg(t::text, '|' ORDER BY t.id), '') FROM wallet_transactions t",
                String.class) + jdbcTemplate.queryForObject("SELECT string_agg(w::text, '|' ORDER BY w.id) FROM wallets w", String.class);
    }

    private String counts() {
        return jdbcTemplate.queryForObject("SELECT 'rounds ' || (SELECT count(*) FROM roulette_rounds) || ', bets ' "
                + "|| (SELECT count(*) FROM roulette_bets)", String.class);
    }

    private void assertNothingChanged(String ledgerBefore) {
        assertThat(ledger()).isEqualTo(ledgerBefore);
        assertThat(counts()).isEqualTo("rounds 0, bets 0");
    }

    /**
     * Fault injection in this test's own container only: every insert into {@code table} fails like a
     * constraint violation (SQLSTATE 23514) while {@code action} runs; the trigger is always removed.
     */
    private void withInjectedFailure(String table, Runnable action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION w406_injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected failure' USING ERRCODE = 'check_violation'; END $$""");
        jdbcTemplate.execute("CREATE TRIGGER w406_injected BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION w406_injected_failure()");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS w406_injected ON " + table);
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS w406_injected_failure()");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
