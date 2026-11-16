package com.casino.game.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.game.entity.RouletteBet;
import com.casino.game.entity.RouletteRound;
import com.casino.game.model.RouletteBetCategory;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteRange;
import com.casino.game.model.RouletteSelection;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.repository.WalletRepository;
import com.casino.wallet.service.WalletService;
import jakarta.persistence.EntityManager;
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
 * {@link RouletteRoundRepository} and the V9 entities against PostgreSQL (W4-05, AC-W4-05.3–05.5). The
 * ledger entries a round references are written through the existing {@link WalletService} in the same
 * transaction, exactly as the round service will (W4-06); nothing here plays a round or draws a number.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RouletteRoundRepositoryIT {

    @Autowired
    private RouletteRoundRepository rounds;

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

    @Autowired
    private EntityManager entityManager;

    /** Nanosecond precision on purpose: the round keeps microseconds, as the database does. */
    private static final Instant PLAYED_AT = Instant.parse("2026-11-20T10:15:30.123456789Z");

    private TransactionTemplate transactions;
    private WalletTestData data;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        data = new WalletTestData(jdbcTemplate, wallets, walletService, transactions);
    }

    @AfterEach
    void resetDatabase() {
        // Also proves that the cleanup list contains the roulette tables (they reference users and the ledger).
        testDatabase.reset();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM roulette_rounds", Long.class)).isZero();
    }

    @Test
    void aRoundIsStoredWithItsBetsAndFoundByPlayerAndRequest() {
        UUID player = data.openedPlayer("alice@example.com");
        UUID request = UUID.randomUUID();
        // Deliberately not in category order: the request order must survive.
        List<Placed> bets = List.of(new Placed(new Dozen(RouletteDozen.SECOND), 15, 45), new Placed(new Straight(17), 10, 360),
                new Placed(new RedBlack(RouletteBetColor.RED), 20, 0), new Placed(new EvenOdd(RouletteParity.ODD), 5, 10),
                new Placed(new LowHigh(RouletteRange.HIGH), 5, 0));

        UUID roundId = store(player, request, 17, bets);

        RouletteRound found = transactions.execute(status -> {
            RouletteRound round = rounds.findByUserIdAndRequestId(player, request).orElseThrow();
            round.getBets().size(); // loaded inside the transaction (entity graph)
            return round;
        });
        assertThat(found.getId()).isEqualTo(roundId);
        assertThat(found.getUserId()).isEqualTo(player);
        assertThat(found.getRequestId()).isEqualTo(request);
        assertThat(found.getWinningNumber()).isEqualTo(17);
        assertThat(found.getTotalStake()).isEqualTo(55);
        assertThat(found.getTotalPayout()).isEqualTo(415);
        assertThat(found.getBalanceAfter()).isEqualTo(1000 - 55 + 415);
        assertThat(found.getCreatedAt()).isEqualTo(Instant.parse("2026-11-20T10:15:30.123456Z"));
        assertThat(found.isNew()).isFalse();
        assertThat(found.getBets()).extracting(RouletteBet::selection)
                .containsExactly(bets.stream().map(Placed::selection).toArray(RouletteSelection[]::new));
        assertThat(found.getBets()).extracting(RouletteBet::getAmount).containsExactly(15L, 10L, 20L, 5L, 5L);
        assertThat(found.getBets()).extracting(RouletteBet::getPayout).containsExactly(45L, 360L, 0L, 10L, 0L);
        assertThat(found.getBets()).extracting(RouletteBet::getCategory).containsExactly(RouletteBetCategory.DOZEN,
                RouletteBetCategory.STRAIGHT, RouletteBetCategory.RED_BLACK, RouletteBetCategory.EVEN_ODD, RouletteBetCategory.LOW_HIGH);
        assertThat(found.getBets()).extracting(RouletteBet::getId).isSorted();
        // The stored row references the round's own stake and payout entries.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT s.type || ' ' || s.amount || ' ' || s.reference || ' / ' || p.type || ' ' || p.amount || ' ' || p.reference
                FROM roulette_rounds r JOIN wallet_transactions s ON s.id = r.stake_transaction_id
                JOIN wallet_transactions p ON p.id = r.payout_transaction_id WHERE r.id = ?""", String.class, roundId))
                .isEqualTo("ROULETTE_STAKE -55 " + roundId + " / ROULETTE_PAYOUT 415 " + roundId);
        // The stored option columns follow Q19.
        assertThat(jdbcTemplate.queryForList("SELECT category || ':' || coalesce(selection, '-') || ':' || coalesce(number::text, '-') "
                + "FROM roulette_bets WHERE round_id = ? ORDER BY id", String.class, roundId))
                .containsExactly("DOZEN:SECOND:-", "STRAIGHT:-:17", "RED_BLACK:RED:-", "EVEN_ODD:ODD:-", "LOW_HIGH:HIGH:-");
        assertThat(data.ledgerViolations()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
    }

    @Test
    void aLosingRoundHasOnlyItsStakeEntry() {
        UUID player = data.openedPlayer("bob@example.com");
        UUID request = UUID.randomUUID();

        UUID roundId = store(player, request, 0, List.of(new Placed(new EvenOdd(RouletteParity.EVEN), 50, 0)));

        RouletteRound found = transactions.execute(status -> rounds.findByUserIdAndRequestId(player, request).orElseThrow());
        assertThat(found.getId()).isEqualTo(roundId);
        assertThat(found.getTotalPayout()).isZero();
        assertThat(found.getPayoutTransactionId()).isNull();
        assertThat(found.getBalanceAfter()).isEqualTo(950);
        assertThat(found.getBets()).singleElement().satisfies(bet -> assertThat(bet.selection()).isEqualTo(new EvenOdd(RouletteParity.EVEN)));
    }

    @Test
    void theLookupIsScopedToThePlayerAndTheRequest() {
        UUID alice = data.openedPlayer("alice@example.com");
        UUID bob = data.openedPlayer("bob@example.com");
        UUID request = UUID.randomUUID();
        store(alice, request, 5, List.of(new Placed(new Straight(5), 10, 360)));

        assertThat(rounds.findByUserIdAndRequestId(alice, request)).isPresent();
        assertThat(rounds.findByUserIdAndRequestId(bob, request)).isEmpty();
        assertThat(rounds.findByUserIdAndRequestId(alice, UUID.randomUUID())).isEmpty();
        // The same request id of another player is another request.
        store(bob, request, 5, List.of(new Placed(new Straight(5), 10, 360)));
        assertThat(rounds.findByUserIdAndRequestId(bob, request)).isPresent();
    }

    @Test
    void roundsCanOnlyBeInsertedAndRead() {
        // The repository offers no update or delete, the entities no setters, the bets list is read-only.
        assertThat(Arrays.stream(RouletteRoundRepository.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("save", "findByUserIdAndRequestId");
        for (Class<?> entity : List.of(RouletteRound.class, RouletteBet.class)) {
            assertThat(Arrays.stream(entity.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("set")))
                    .as(entity.getSimpleName()).isEmpty();
        }
        UUID player = data.openedPlayer("carol@example.com");
        UUID request = UUID.randomUUID();
        store(player, request, 9, List.of(new Placed(new Dozen(RouletteDozen.FIRST), 10, 30)));
        RouletteRound found = transactions.execute(status -> rounds.findByUserIdAndRequestId(player, request).orElseThrow());
        assertThatThrownBy(() -> found.getBets().clear()).isInstanceOf(UnsupportedOperationException.class);
        // Reading and committing writes nothing (an UPDATE would hit the append-only trigger).
        String before = jdbcTemplate.queryForObject("SELECT string_agg(r::text, '|') FROM roulette_rounds r", String.class);
        transactions.executeWithoutResult(status -> rounds.findByUserIdAndRequestId(player, request).orElseThrow().getBets().size());
        assertThat(jdbcTemplate.queryForObject("SELECT string_agg(r::text, '|') FROM roulette_rounds r", String.class)).isEqualTo(before);
        // And the database refuses changes from any client.
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE roulette_rounds SET winning_number = 1"))
                .hasMessageContaining("roulette_rounds is append-only: UPDATE rejected");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM roulette_bets"))
                .hasMessageContaining("roulette_bets is append-only: DELETE rejected");
    }

    @Test
    void aRejectedRoundLeavesNothingNotEvenItsLedgerEntries() {
        UUID player = data.openedPlayer("dave@example.com");
        UUID request = UUID.randomUUID();
        store(player, request, 3, List.of(new Placed(new Straight(3), 10, 360)));
        String ledgerBefore = ledger();

        // The same request again: uq_roulette_rounds_request. The stake and payout written in the same
        // transaction roll back with it (W4-06 relies on this; it also takes the wallet lock first).
        assertThatThrownBy(() -> store(player, request, 4, List.of(new Placed(new Straight(4), 10, 360))))
                .hasStackTraceContaining("uq_roulette_rounds_request");
        // The same selection twice in one round: uq_roulette_bets_selection.
        assertThatThrownBy(() -> store(player, UUID.randomUUID(), 4, List.of(new Placed(new RedBlack(RouletteBetColor.RED), 10, 0),
                new Placed(new RedBlack(RouletteBetColor.RED), 5, 0)))).hasStackTraceContaining("uq_roulette_bets_selection");
        // A payout entry without a payout: ck_roulette_rounds_payout_entry.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID id = UUID.randomUUID();
            WalletOperationResult stake = stake(player, id, 10);
            WalletOperationResult payout = walletService.credit(player, new WalletOperation(WalletTransactionType.ROULETTE_PAYOUT, 20,
                    "roulette-round:" + id + ":payout", id.toString()));
            rounds.save(new RouletteRound(id, player, UUID.randomUUID(), 4, 10, 0, payout.balanceAfter(), stake.transactionId(),
                    payout.transactionId(), List.of(RouletteBet.of(new Straight(5), 10, 0)), PLAYED_AT));
            entityManager.flush();
        })).hasStackTraceContaining("ck_roulette_rounds_payout_entry");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM roulette_rounds", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM roulette_bets", Long.class)).isOne();
        assertThat(ledger()).isEqualTo(ledgerBefore);
        assertThat(data.ledgerViolations()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
    }

    @Test
    void aRoundNeedsAtLeastOneBet() {
        assertThatThrownBy(() -> new RouletteRound(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 10, 0, 0, 1, null,
                List.of(), PLAYED_AT)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    /** A bet with its payout as the round service will compute it (RouletteRules, W4-06). */
    private record Placed(RouletteSelection selection, long amount, long payout) {
    }

    /**
     * Stores a round as W4-06 will, in one transaction: the stake debit, the payout credit if anything was
     * won, then the round with its bets (flushed, so constraint violations surface here).
     */
    private UUID store(UUID player, UUID request, int winningNumber, List<Placed> bets) {
        return transactions.execute(status -> {
            UUID id = UUID.randomUUID();
            long totalStake = bets.stream().mapToLong(Placed::amount).sum();
            long totalPayout = bets.stream().mapToLong(Placed::payout).sum();
            WalletOperationResult stake = stake(player, id, totalStake);
            WalletOperationResult payout = totalPayout == 0 ? null : walletService.credit(player, new WalletOperation(
                    WalletTransactionType.ROULETTE_PAYOUT, totalPayout, "roulette-round:" + id + ":payout", id.toString()));
            long balanceAfter = payout == null ? stake.balanceAfter() : payout.balanceAfter();
            rounds.save(new RouletteRound(id, player, request, winningNumber, totalStake, totalPayout, balanceAfter,
                    stake.transactionId(), payout == null ? null : payout.transactionId(),
                    bets.stream().map(bet -> RouletteBet.of(bet.selection(), bet.amount(), bet.payout())).toList(), PLAYED_AT));
            entityManager.flush();
            return id;
        });
    }

    private WalletOperationResult stake(UUID player, UUID round, long amount) {
        return walletService.debit(player, new WalletOperation(WalletTransactionType.ROULETTE_STAKE, amount,
                "roulette-round:" + round + ":stake", round.toString()));
    }

    private String ledger() {
        return jdbcTemplate.queryForObject("SELECT coalesce(string_agg(t::text, '|' ORDER BY t.id), '') FROM wallet_transactions t",
                String.class) + jdbcTemplate.queryForObject("SELECT string_agg(w::text, '|' ORDER BY w.id) FROM wallets w", String.class);
    }
}
