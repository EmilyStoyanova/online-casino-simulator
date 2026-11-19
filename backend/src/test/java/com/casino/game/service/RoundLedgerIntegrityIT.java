package com.casino.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.casino.ReadOnlyScripts;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.game.model.PlayedRound;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteRange;
import com.casino.game.model.RouletteRules;
import com.casino.game.model.RouletteSelection;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.RouletteSettlement;
import com.casino.game.model.RouletteWager;
import com.casino.game.model.SettledWager;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
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
 * Round–ledger integrity over a seeded sequence of spins (W4-08, AC-W4-08.4; {@code docs/plans/week-04.md} §5.5):
 * three players, random bets of every category (1–6 per spin), a fixed wheel sequence with several zeros,
 * replays with reordered bets, reused keys with other bets, unaffordable spins and an injected failure at the
 * round insert. After <em>every</em> step the database is compared with an in-memory model (balances, rounds,
 * ledger entries, draws) and reconciled read-only ({@code verify-roulette.sql}, {@code verify-wallets.sql},
 * the Week 3 ledger invariants). At the end every stored round is checked in Java against §5.5, against
 * {@link RouletteRules} and against the result its spin returned.
 *
 * <p>The sequence is deterministic: a fixed seed for the bets and actions, a fixed list of winning numbers.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoundLedgerIntegrityIT {

    private static final long SEED = 20261119L;
    private static final int STEPS = 90;
    /** Winning numbers in draw order: zero four times, red and black, even and odd, low and high, all dozens. */
    private static final int[] DRAWS = {17, 0, 32, 8, 36, 1, 0, 19, 24, 13, 35, 2, 0, 18, 25, 12, 33, 7, 0, 26, 3, 30};

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

    private WalletTestData data;
    private final AtomicInteger draws = new AtomicInteger();

    // The model: what the database must contain.
    private final Map<UUID, Long> balances = new HashMap<>();
    private final List<Played> history = new ArrayList<>();
    private final Map<String, Integer> events = new TreeMap<>();
    private int expectedDraws;

    @BeforeEach
    void setUp() {
        data = new WalletTestData(jdbcTemplate, wallets, walletService, new TransactionTemplate(transactionManager));
        when(wheel.spin()).thenAnswer(invocation -> DRAWS[draws.getAndIncrement() % DRAWS.length]);
    }

    @AfterEach
    void reset() {
        testDatabase.reset();
    }

    @Test
    void aSeededSequenceOfRoundsKeepsEveryInvariant() throws SQLException {
        Random random = new Random(SEED);
        List<UUID> players = new ArrayList<>();
        for (String email : List.of("alice@example.com", "bob@example.com", "carol@example.com")) {
            UUID player = data.openedPlayer(email);
            players.add(player);
            balances.put(player, 1000L);
        }

        for (int step = 0; step < STEPS; step++) {
            int action = random.nextInt(100);
            if (history.isEmpty() || action < 62) {
                newSpin(players.get(random.nextInt(players.size())), randomWagers(random));
            } else if (action < 78) {
                replay(history.get(random.nextInt(history.size())), random);
            } else if (action < 92) {
                reuseKeyWithOtherBets(history.get(random.nextInt(history.size())));
            } else {
                failAtTheRoundInsertThenRetry(players.get(random.nextInt(players.size())), random);
            }
            assertTheDatabaseMatchesTheModel("step " + step);
        }

        assertEveryStoredRoundFollowsTheRules();
        // The sequence covers what it is meant to cover (a changed seed or draw list must still do so).
        assertThat(history.stream().map(Played::player).distinct()).hasSize(3);
        assertThat(history).anySatisfy(round -> assertThat(round.result().winningNumber()).isZero());
        assertThat(history).anySatisfy(round -> assertThat(round.result().totalPayout()).isZero());
        assertThat(history).anySatisfy(round -> assertThat(round.result().totalPayout()).isPositive());
        assertThat(history).anySatisfy(round -> assertThat(round.result().bets()).hasSizeGreaterThanOrEqualTo(4));
        assertThat(history).anySatisfy(round -> assertThat(round.result().bets()).hasSize(1));
        assertThat(events).containsKeys("round", "replay", "key reused", "insufficient funds", "injected failure");
        // The sequence is deterministic (seed and draw list): 47 rounds and 57 draws (10 rolled back).
        assertThat(events).isEqualTo(Map.of("round", 47, "replay", 7, "key reused", 9, "insufficient funds", 27, "injected failure", 10));
        assertThat(expectedDraws).isEqualTo(57);
    }

    // ------------------------------------------------------------------ actions

    private void newSpin(UUID player, List<RouletteWager> wagers) {
        UUID request = UUID.randomUUID();
        long stake = wagers.stream().mapToLong(RouletteWager::amount).sum();
        if (stake > balances.get(player)) {
            assertThatThrownBy(() -> roulette.spin(player, request, wagers)).isInstanceOf(InsufficientFundsException.class);
            event("insufficient funds");
            return;
        }
        played(player, request, wagers, roulette.spin(player, request, wagers));
    }

    /** The same request with its bets in another order: the stored result, nothing written, no draw. */
    private void replay(Played earlier, Random random) {
        List<RouletteWager> reordered = new ArrayList<>(earlier.wagers());
        Collections.shuffle(reordered, random);

        assertThat(roulette.spin(earlier.player(), earlier.request(), reordered)).isEqualTo(earlier.result());
        event("replay");
    }

    /** The same key with one amount changed: a conflict, nothing written, no draw. */
    private void reuseKeyWithOtherBets(Played earlier) {
        List<RouletteWager> changed = new ArrayList<>(earlier.wagers());
        RouletteWager first = changed.getFirst();
        changed.set(0, new RouletteWager(first.selection(), first.amount() == 1 ? 2 : first.amount() - 1));

        assertThatThrownBy(() -> roulette.spin(earlier.player(), earlier.request(), changed)).isInstanceOf(IdempotencyKeyReusedException.class);
        event("key reused");
    }

    /**
     * The round insert fails after the debit, the draw and the credit: everything is rolled back, although a
     * number was drawn. The same request then plays normally with the next number.
     */
    private void failAtTheRoundInsertThenRetry(UUID player, Random random) {
        List<RouletteWager> wagers = List.of(new RouletteWager(randomSelection(random), 1 + random.nextInt(10)));
        if (wagers.getFirst().amount() > balances.get(player)) {
            return;
        }
        UUID request = UUID.randomUUID();
        withInjectedFailure(() -> assertThatThrownBy(() -> roulette.spin(player, request, wagers)).hasStackTraceContaining("injected failure"));
        expectedDraws++;
        event("injected failure");
        assertTheDatabaseMatchesTheModel("after the injected failure");

        played(player, request, wagers, roulette.spin(player, request, wagers));
    }

    /** Checks a new round against the rules and the model, then records it. */
    private void played(UUID player, UUID request, List<RouletteWager> wagers, PlayedRound round) {
        int winningNumber = DRAWS[expectedDraws % DRAWS.length];
        expectedDraws++;
        RouletteSettlement settlement = RouletteRules.settle(wagers, winningNumber);
        long balance = balances.get(player) - settlement.totalStake() + settlement.totalPayout();
        List<SettledWager> bets = new ArrayList<>();
        for (int i = 0; i < wagers.size(); i++) {
            bets.add(new SettledWager(wagers.get(i).selection(), wagers.get(i).amount(), settlement.payouts().get(i)));
        }

        assertThat(round.winningNumber()).isEqualTo(winningNumber);
        assertThat(round.winningColor()).isEqualTo(RouletteRules.colorOf(winningNumber));
        assertThat(round.bets()).isEqualTo(bets);
        assertThat(round.totalStake()).isEqualTo(settlement.totalStake());
        assertThat(round.totalPayout()).isEqualTo(settlement.totalPayout());
        assertThat(round.balance()).isEqualTo(balance);
        balances.put(player, balance);
        history.add(new Played(player, request, wagers, round));
        event("round");
    }

    // ------------------------------------------------------------------ checks

    /** Balances, rounds, bets, ledger entries and draws equal the model; the read-only reconciliation finds nothing. */
    private void assertTheDatabaseMatchesTheModel(String when) {
        balances.forEach((player, balance) -> assertThat(data.balance(player)).as("%s: balance", when).isEqualTo(balance));
        assertThat(draws.get()).as("%s: numbers drawn", when).isEqualTo(expectedDraws);
        long winning = history.stream().filter(round -> round.result().totalPayout() > 0).count();
        long bets = history.stream().mapToLong(round -> round.wagers().size()).sum();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM roulette_rounds) || ',' || (SELECT count(*) FROM roulette_bets) || ','
                    || (SELECT count(*) FROM wallet_transactions WHERE type = 'ROULETTE_STAKE') || ','
                    || (SELECT count(*) FROM wallet_transactions WHERE type = 'ROULETTE_PAYOUT')""", String.class))
                .as("%s: rounds, bets, stake entries, payout entries", when)
                .isEqualTo(history.size() + "," + bets + "," + history.size() + "," + winning);
        try (Connection connection = dataSource.getConnection()) {
            List<String> roulette = ReadOnlyScripts.run(connection, "verify-roulette.sql");
            assertThat(roulette.get(0)).as("%s: verify-roulette.sql", when).isEqualTo("0,0,0,0,0,0,0,0,0,0,0");
            assertThat(roulette.get(1)).as("%s: verify-roulette.sql counts", when).isEqualTo(history.size() + "," + bets + ","
                    + history.stream().map(Played::player).distinct().count() + "," + winning + "," + history.size() + "," + winning);
            assertThat(ReadOnlyScripts.run(connection, "verify-wallets.sql").get(0)).as("%s: verify-wallets.sql", when).isEqualTo("0,0,0,0");
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(data.ledgerViolations()).allSatisfy((name, count) -> assertThat(count).as("%s: %s", when, name).isZero());
    }

    /** §5.5 for every stored round, checked in Java (independently of the SQL script), and equal to its spin's result. */
    private void assertEveryStoredRoundFollowsTheRules() {
        Map<UUID, PlayedRound> results = new HashMap<>();
        history.forEach(round -> results.put(round.result().roundId(), round.result()));
        Set<Long> referenced = new HashSet<>();
        List<Map<String, Object>> rounds = jdbcTemplate.queryForList("SELECT * FROM roulette_rounds");
        assertThat(rounds).hasSize(history.size());

        for (Map<String, Object> round : rounds) {
            UUID id = (UUID) round.get("id");
            UUID player = (UUID) round.get("user_id");
            int winningNumber = ((Number) round.get("winning_number")).intValue();
            long totalStake = (Long) round.get("total_stake");
            long totalPayout = (Long) round.get("total_payout");
            long stakeId = (Long) round.get("stake_transaction_id");
            Long payoutId = (Long) round.get("payout_transaction_id");
            String what = "round " + id;

            // 1, 2: the bets add up, and each pays what RouletteRules pays for the winning number.
            List<SettledWager> bets = jdbcTemplate.query("SELECT category, selection, number, amount, payout FROM roulette_bets "
                    + "WHERE round_id = ? ORDER BY id", (row, i) -> new SettledWager(selection(row.getString("category"),
                    row.getString("selection"), (Integer) row.getObject("number")), row.getLong("amount"), row.getLong("payout")), id);
            assertThat(bets).as(what).hasSizeBetween(1, 10);
            assertThat(bets.stream().mapToLong(SettledWager::amount).sum()).as(what).isEqualTo(totalStake);
            assertThat(bets.stream().mapToLong(SettledWager::payout).sum()).as(what).isEqualTo(totalPayout);
            bets.forEach(bet -> assertThat(bet.payout()).as("%s: %s", what, bet)
                    .isEqualTo(RouletteRules.payout(bet.selection(), bet.amount(), winningNumber)));

            // 3: the stake entry is the round's own.
            assertThat(entry(stakeId)).as(what).isEqualTo(new Entry(player, "ROULETTE_STAKE", -totalStake, id.toString(),
                    "roulette-round:" + id + ":stake"));
            referenced.add(stakeId);
            // 4: the payout entry exists exactly when something was won, is the round's own and newer than the stake.
            assertThat(payoutId == null).as(what).isEqualTo(totalPayout == 0);
            if (payoutId != null) {
                assertThat(entry(payoutId)).as(what).isEqualTo(new Entry(player, "ROULETTE_PAYOUT", totalPayout, id.toString(),
                        "roulette-round:" + id + ":payout"));
                assertThat(payoutId).as(what).isGreaterThan(stakeId);
                referenced.add(payoutId);
            }
            // 5: the stored balance is the one after the round's last entry.
            assertThat(round.get("balance_after")).as(what).isEqualTo(jdbcTemplate.queryForObject(
                    "SELECT balance_after FROM wallet_transactions WHERE id = ?", Long.class, payoutId == null ? stakeId : payoutId));

            // The stored round is exactly what its spin returned (and what every replay returned).
            PlayedRound result = results.get(id);
            assertThat(result).as(what).isNotNull();
            assertThat(new PlayedRound(id, winningNumber, RouletteRules.colorOf(winningNumber), bets, totalStake, totalPayout,
                    (Long) round.get("balance_after"), result.createdAt())).as(what).isEqualTo(result);
            assertThat(result.createdAt()).as(what).isEqualTo(jdbcTemplate.queryForObject("SELECT created_at FROM roulette_rounds WHERE id = ?",
                    java.time.OffsetDateTime.class, id).toInstant());
        }

        // 6: every roulette entry belongs to exactly one round (each id was referenced once above).
        List<Long> rouletteEntries = jdbcTemplate.queryForList(
                "SELECT id FROM wallet_transactions WHERE type IN ('ROULETTE_STAKE', 'ROULETTE_PAYOUT')", Long.class);
        assertThat(new HashSet<>(rouletteEntries)).isEqualTo(referenced);
        assertThat(rouletteEntries).hasSameSizeAs(referenced);
    }

    private record Entry(UUID owner, String type, long amount, String reference, String key) {
    }

    private Entry entry(long id) {
        return jdbcTemplate.queryForObject("""
                SELECT w.user_id, t.type, t.amount, t.reference, t.idempotency_key
                FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE t.id = ?""",
                (row, i) -> new Entry(row.getObject(1, UUID.class), row.getString(2), row.getLong(3), row.getString(4), row.getString(5)), id);
    }

    // ------------------------------------------------------------------ helpers

    private record Played(UUID player, UUID request, List<RouletteWager> wagers, PlayedRound result) {
    }

    private void event(String name) {
        events.merge(name, 1, Integer::sum);
    }

    /** 1–6 bets with different selections, 1–150 coins each. */
    private static List<RouletteWager> randomWagers(Random random) {
        Set<RouletteSelection> selections = new LinkedHashSet<>();
        int count = 1 + random.nextInt(6);
        while (selections.size() < count) {
            selections.add(randomSelection(random));
        }
        List<RouletteWager> wagers = new ArrayList<>();
        selections.forEach(selection -> wagers.add(new RouletteWager(selection, 1 + random.nextInt(150))));
        return wagers;
    }

    private static RouletteSelection randomSelection(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> new Straight(random.nextInt(37));
            case 1 -> new RedBlack(RouletteBetColor.values()[random.nextInt(2)]);
            case 2 -> new EvenOdd(RouletteParity.values()[random.nextInt(2)]);
            case 3 -> new LowHigh(RouletteRange.values()[random.nextInt(2)]);
            default -> new Dozen(RouletteDozen.values()[random.nextInt(3)]);
        };
    }

    /** The selection stored in the Q19 columns of {@code roulette_bets}. */
    private static RouletteSelection selection(String category, String option, Integer number) {
        return switch (category) {
            case "STRAIGHT" -> new Straight(number);
            case "RED_BLACK" -> new RedBlack(RouletteBetColor.valueOf(option));
            case "EVEN_ODD" -> new EvenOdd(RouletteParity.valueOf(option));
            case "LOW_HIGH" -> new LowHigh(RouletteRange.valueOf(option));
            case "DOZEN" -> new Dozen(RouletteDozen.valueOf(option));
            default -> throw new AssertionError("Unknown category " + category);
        };
    }

    /**
     * Fault injection in this test's own container only: every insert into {@code roulette_rounds} fails like a
     * constraint violation (SQLSTATE 23514) while {@code action} runs; the trigger is always removed.
     */
    private void withInjectedFailure(Runnable action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION w408_injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected failure' USING ERRCODE = 'check_violation'; END $$""");
        jdbcTemplate.execute("CREATE TRIGGER w408_injected BEFORE INSERT ON roulette_rounds FOR EACH ROW EXECUTE FUNCTION w408_injected_failure()");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS w408_injected ON roulette_rounds");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS w408_injected_failure()");
        }
    }
}
