package com.casino.game.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The European roulette rules over their whole domain (W4-04, AC-W4-04.1–04.3): every result 0–36 against
 * every selection (37 straight numbers and the 9 options of the other four categories). The expected
 * winning numbers are written out independently of {@link RouletteRules} (literal lists and ranges), so
 * the test does not repeat the implementation.
 */
class RouletteRulesTest {

    /** The standard red numbers, as printed on a European table layout. */
    private static final Set<Integer> RED = Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);
    private static final Set<Integer> BLACK = Set.of(2, 4, 6, 8, 10, 11, 13, 15, 17, 20, 22, 24, 26, 28, 29, 31, 33, 35);

    /** Every selection with the numbers it covers. */
    static Stream<Covered> everySelection() {
        List<Covered> all = new ArrayList<>();
        IntStream.rangeClosed(0, 36).forEach(n -> all.add(new Covered(new Straight(n), Set.of(n))));
        all.add(new Covered(new RedBlack(RouletteBetColor.RED), RED));
        all.add(new Covered(new RedBlack(RouletteBetColor.BLACK), BLACK));
        all.add(new Covered(new EvenOdd(RouletteParity.EVEN), range(1, 36, n -> n % 2 == 0)));
        all.add(new Covered(new EvenOdd(RouletteParity.ODD), range(1, 36, n -> n % 2 == 1)));
        all.add(new Covered(new LowHigh(RouletteRange.LOW), range(1, 18, n -> true)));
        all.add(new Covered(new LowHigh(RouletteRange.HIGH), range(19, 36, n -> true)));
        all.add(new Covered(new Dozen(RouletteDozen.FIRST), range(1, 12, n -> true)));
        all.add(new Covered(new Dozen(RouletteDozen.SECOND), range(13, 24, n -> true)));
        all.add(new Covered(new Dozen(RouletteDozen.THIRD), range(25, 36, n -> true)));
        return all.stream();
    }

    // ------------------------------------------------------------------ the wheel

    @Test
    void theWheelHasThirtySevenNumbersWithTheStandardColors() {
        assertThat(RouletteRules.POCKETS).isEqualTo(37);
        assertThat(RouletteRules.HIGHEST_NUMBER).isEqualTo(36);
        assertThat(RouletteRules.colorOf(0)).isEqualTo(RouletteNumberColor.GREEN);
        for (int n = 1; n <= 36; n++) {
            RouletteNumberColor expected = RED.contains(n) ? RouletteNumberColor.RED : RouletteNumberColor.BLACK;
            assertThat(RouletteRules.colorOf(n)).as("color of %d", n).isEqualTo(expected);
            // The layout rule: in 1–10 and 19–28 the odd numbers are red, in 11–18 and 29–36 the even ones.
            boolean oddIsRed = n <= 10 || (n >= 19 && n <= 28);
            assertThat(RED.contains(n)).as("layout rule for %d", n).isEqualTo(oddIsRed == (n % 2 == 1));
        }
        assertThat(RED).hasSize(18).doesNotContainAnyElementsOf(BLACK);
        assertThat(BLACK).hasSize(18);
    }

    @Test
    void onlyNumbersFromZeroToThirtySixExist() {
        for (int invalid : new int[] {-1, 37, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().as("%d", invalid).isThrownBy(() -> RouletteRules.colorOf(invalid));
            assertThatIllegalArgumentException().as("%d", invalid)
                    .isThrownBy(() -> RouletteRules.wins(new RedBlack(RouletteBetColor.RED), invalid));
            assertThatIllegalArgumentException().as("%d", invalid)
                    .isThrownBy(() -> RouletteRules.payout(new Straight(0), 10, invalid));
            assertThatIllegalArgumentException().as("%d", invalid)
                    .isThrownBy(() -> RouletteRules.settle(List.of(new RouletteWager(new Straight(0), 10)), invalid));
            assertThatIllegalArgumentException().as("%d", invalid).isThrownBy(() -> new Straight(invalid))
                    .withMessage("A roulette number is between 0 and 36");
        }
    }

    // ------------------------------------------------------------------ coverage of every selection

    @ParameterizedTest(name = "{0}")
    @MethodSource("everySelection")
    void everySelectionWinsExactlyOnItsNumbers(Covered covered) {
        Set<Integer> winning = IntStream.rangeClosed(0, 36).filter(n -> RouletteRules.wins(covered.selection(), n))
                .boxed().collect(Collectors.toCollection(TreeSet::new));

        assertThat(winning).isEqualTo(new TreeSet<>(covered.numbers()));
        int expectedCount = switch (covered.selection().category()) {
            case STRAIGHT -> 1;
            case RED_BLACK, EVEN_ODD, LOW_HIGH -> 18;
            case DOZEN -> 12;
        };
        assertThat(winning).hasSize(expectedCount);
    }

    @Test
    void zeroWinsOnlyAStraightBetOnZero() {
        List<RouletteSelection> winnersOnZero = everySelection().map(Covered::selection)
                .filter(selection -> RouletteRules.wins(selection, 0)).toList();

        assertThat(winnersOnZero).containsExactly(new Straight(0));
        assertThat(RouletteRules.payout(new Straight(0), 10, 0)).isEqualTo(360);
        for (RouletteSelection outside : List.of(new RedBlack(RouletteBetColor.RED), new RedBlack(RouletteBetColor.BLACK),
                new EvenOdd(RouletteParity.EVEN), new EvenOdd(RouletteParity.ODD), new LowHigh(RouletteRange.LOW),
                new LowHigh(RouletteRange.HIGH), new Dozen(RouletteDozen.FIRST), new Dozen(RouletteDozen.SECOND),
                new Dozen(RouletteDozen.THIRD))) {
            assertThat(RouletteRules.payout(outside, 10, 0)).as("%s on 0", outside).isZero();
        }
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void theCategoriesHaveTheStandardOdds() {
        assertThat(RouletteBetCategory.values()).containsExactly(RouletteBetCategory.STRAIGHT, RouletteBetCategory.RED_BLACK,
                RouletteBetCategory.EVEN_ODD, RouletteBetCategory.LOW_HIGH, RouletteBetCategory.DOZEN);
        assertThat(RouletteBetCategory.STRAIGHT.netOdds()).isEqualTo(35);
        assertThat(RouletteBetCategory.RED_BLACK.netOdds()).isEqualTo(1);
        assertThat(RouletteBetCategory.EVEN_ODD.netOdds()).isEqualTo(1);
        assertThat(RouletteBetCategory.LOW_HIGH.netOdds()).isEqualTo(1);
        assertThat(RouletteBetCategory.DOZEN.netOdds()).isEqualTo(2);
        for (RouletteBetCategory category : RouletteBetCategory.values()) {
            assertThat(category.payoutMultiplier()).as(category.name()).isEqualTo(category.netOdds() + 1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everySelection")
    void aWinReturnsTheStakePlusTheNetWinningsAndALossPaysNothing(Covered covered) {
        RouletteSelection selection = covered.selection();
        int netOdds = selection.category().netOdds();
        long amount = 25;
        for (int n = 0; n <= 36; n++) {
            long payout = RouletteRules.payout(selection, amount, n);
            long net = payout - amount;
            if (covered.numbers().contains(n)) {
                assertThat(net).as("net winnings of %s on %d", selection, n).isEqualTo(amount * netOdds);
                assertThat(payout).as("payout of %s on %d", selection, n).isEqualTo(amount * (netOdds + 1));
            } else {
                assertThat(payout).as("payout of %s on %d", selection, n).isZero();
                assertThat(net).as("net result of %s on %d", selection, n).isEqualTo(-amount);
            }
        }
    }

    @Test
    void thePayoutsOfTheContractExamples() {
        // openapi.yaml 0.5.0, spin examples "win", "zero" and "loss".
        assertThat(RouletteRules.payout(new Straight(17), 10, 17)).isEqualTo(360);
        assertThat(RouletteRules.payout(new RedBlack(RouletteBetColor.RED), 20, 17)).isZero();
        assertThat(RouletteRules.payout(new Straight(0), 5, 0)).isEqualTo(180);
        assertThat(RouletteRules.payout(new EvenOdd(RouletteParity.EVEN), 10, 0)).isZero();
        assertThat(RouletteRules.payout(new Dozen(RouletteDozen.THIRD), 50, 8)).isZero();
        // Even money and dozens on a win.
        assertThat(RouletteRules.payout(new RedBlack(RouletteBetColor.BLACK), 20, 17)).isEqualTo(40);
        assertThat(RouletteRules.payout(new LowHigh(RouletteRange.LOW), 20, 17)).isEqualTo(40);
        assertThat(RouletteRules.payout(new EvenOdd(RouletteParity.ODD), 20, 17)).isEqualTo(40);
        assertThat(RouletteRules.payout(new Dozen(RouletteDozen.SECOND), 20, 17)).isEqualTo(60);
    }

    @Test
    void theLargestApprovedStakesPayExactly() {
        // API limits (Q2): at most 1,000 coins per bet and 10 bets per spin.
        assertThat(RouletteRules.payout(new Straight(36), 1000, 36)).isEqualTo(36_000);
        assertThat(RouletteRules.payout(new RedBlack(RouletteBetColor.RED), 1000, 36)).isEqualTo(2_000);
        assertThat(RouletteRules.payout(new Dozen(RouletteDozen.THIRD), 1000, 36)).isEqualTo(3_000);
        // The best possible spin within the limits: every selection that 36 wins, plus five other straights.
        List<RouletteWager> best = new ArrayList<>(List.of(new RouletteWager(new Straight(36), 1000),
                new RouletteWager(new RedBlack(RouletteBetColor.RED), 1000), new RouletteWager(new EvenOdd(RouletteParity.EVEN), 1000),
                new RouletteWager(new LowHigh(RouletteRange.HIGH), 1000), new RouletteWager(new Dozen(RouletteDozen.THIRD), 1000)));
        IntStream.rangeClosed(1, 5).forEach(n -> best.add(new RouletteWager(new Straight(n), 1000)));

        RouletteSettlement settlement = RouletteRules.settle(best, 36);

        assertThat(settlement.totalStake()).isEqualTo(10_000);
        assertThat(settlement.totalPayout()).isEqualTo(36_000 + 2_000 + 2_000 + 2_000 + 3_000);
        assertThat(settlement.netResult()).isEqualTo(35_000);
    }

    @Test
    void anOverflowFailsInsteadOfWrapping() {
        long tooLarge = Long.MAX_VALUE / 36 + 1;
        assertThatThrownBy(() -> RouletteRules.payout(new Straight(7), tooLarge, 7)).isInstanceOf(ArithmeticException.class);
        // A losing bet of the same amount pays nothing and does not overflow.
        assertThat(RouletteRules.payout(new Straight(7), tooLarge, 8)).isZero();
        // Totals are exact too.
        List<RouletteWager> huge = List.of(new RouletteWager(new Straight(1), Long.MAX_VALUE), new RouletteWager(new Straight(2), 1));
        assertThatThrownBy(() -> RouletteRules.settle(huge, 3)).isInstanceOf(ArithmeticException.class);
    }

    // ------------------------------------------------------------------ expected value (exact)

    @ParameterizedTest(name = "{0}")
    @MethodSource("everySelection")
    void everyBetLosesExactlyOneThirtySeventhOfItsAmountOnAverage(Covered covered) {
        for (long amount : new long[] {1, 7, 1000}) {
            long sumOfPayouts = 0;
            long sumOfNetResults = 0;
            for (int n = 0; n <= 36; n++) {
                long payout = RouletteRules.payout(covered.selection(), amount, n);
                sumOfPayouts += payout;
                sumOfNetResults += payout - amount;
            }
            // Over the 37 equally likely results: E[payout] = 36a/37 (RTP 36/37), E[net] = −a/37 (house edge 1/37).
            assertThat(sumOfPayouts).as("%s, amount %d", covered.selection(), amount).isEqualTo(36 * amount);
            assertThat(sumOfNetResults).as("%s, amount %d", covered.selection(), amount).isEqualTo(-amount);
        }
    }

    @Test
    void theExpectedValueFollowsFromTheNetOddsOfEveryCategory() {
        // P(win) × net odds − P(loss) × 1, as an exact fraction over 37: always −1/37.
        for (RouletteBetCategory category : RouletteBetCategory.values()) {
            int winningNumbers = switch (category) {
                case STRAIGHT -> 1;
                case RED_BLACK, EVEN_ODD, LOW_HIGH -> 18;
                case DOZEN -> 12;
            };
            int numeratorOverThirtySeven = winningNumbers * category.netOdds() - (37 - winningNumbers);
            assertThat(numeratorOverThirtySeven).as(category.name()).isEqualTo(-1);
            assertThat(winningNumbers * category.payoutMultiplier()).as(category.name()).isEqualTo(36);
        }
        BigDecimal houseEdgePercent = BigDecimal.valueOf(100).divide(BigDecimal.valueOf(37), 4, RoundingMode.HALF_UP);
        BigDecimal rtpPercent = BigDecimal.valueOf(3600).divide(BigDecimal.valueOf(37), 4, RoundingMode.HALF_UP);
        assertThat(houseEdgePercent).isEqualByComparingTo("2.7027");
        assertThat(rtpPercent).isEqualByComparingTo("97.2973");
    }

    // ------------------------------------------------------------------ settlement and invalid input

    @Test
    void aSettlementKeepsTheOrderOfTheWagersAndAddsUpExactly() {
        RouletteSettlement settlement = RouletteRules.settle(List.of(new RouletteWager(new Straight(17), 10),
                new RouletteWager(new RedBlack(RouletteBetColor.RED), 20)), 17);

        assertThat(settlement.winningNumber()).isEqualTo(17);
        assertThat(settlement.winningColor()).isEqualTo(RouletteNumberColor.BLACK);
        assertThat(settlement.payouts()).containsExactly(360L, 0L);
        assertThat(settlement.totalStake()).isEqualTo(30);
        assertThat(settlement.totalPayout()).isEqualTo(360);
        assertThat(settlement.netResult()).isEqualTo(330);
        // A loss: the net result is the negative stake.
        RouletteSettlement loss = RouletteRules.settle(List.of(new RouletteWager(new Dozen(RouletteDozen.THIRD), 50)), 8);
        assertThat(loss.payouts()).containsExactly(0L);
        assertThat(loss.totalPayout()).isZero();
        assertThat(loss.netResult()).isEqualTo(-50);
        assertThat(loss.winningColor()).isEqualTo(RouletteNumberColor.BLACK);
        // The settlement cannot be changed afterwards.
        assertThatThrownBy(() -> settlement.payouts().add(1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void amountsMustBePositive(long amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> new RouletteWager(new Straight(1), amount));
        assertThatIllegalArgumentException().isThrownBy(() -> RouletteRules.payout(new Straight(1), amount, 1));
    }

    @Test
    void incompleteSelectionsCannotBeCreated() {
        assertThatNullPointerException().isThrownBy(() -> new RedBlack(null)).withMessage("color");
        assertThatNullPointerException().isThrownBy(() -> new EvenOdd(null)).withMessage("parity");
        assertThatNullPointerException().isThrownBy(() -> new LowHigh(null)).withMessage("range");
        assertThatNullPointerException().isThrownBy(() -> new Dozen(null)).withMessage("dozen");
        assertThatNullPointerException().isThrownBy(() -> new RouletteWager(null, 10)).withMessage("selection");
        assertThatNullPointerException().isThrownBy(() -> RouletteRules.wins(null, 1)).withMessage("selection");
        assertThatNullPointerException().isThrownBy(() -> RouletteRules.settle(null, 1)).withMessage("wagers");
        assertThatIllegalArgumentException().isThrownBy(() -> RouletteRules.settle(Collections.emptyList(), 1))
                .withMessage("A spin needs at least one bet");
    }

    @Test
    void eachSelectionBelongsToItsCategoryAndEqualSelectionsAreEqual() {
        assertThat(new Straight(5).category()).isEqualTo(RouletteBetCategory.STRAIGHT);
        assertThat(new RedBlack(RouletteBetColor.RED).category()).isEqualTo(RouletteBetCategory.RED_BLACK);
        assertThat(new EvenOdd(RouletteParity.ODD).category()).isEqualTo(RouletteBetCategory.EVEN_ODD);
        assertThat(new LowHigh(RouletteRange.HIGH).category()).isEqualTo(RouletteBetCategory.LOW_HIGH);
        assertThat(new Dozen(RouletteDozen.FIRST).category()).isEqualTo(RouletteBetCategory.DOZEN);
        // Value semantics: the request validation (W4-07) can detect a selection placed twice.
        assertThat(new Straight(5)).isEqualTo(new Straight(5)).isNotEqualTo(new Straight(6));
        assertThat(new RedBlack(RouletteBetColor.RED)).isEqualTo(new RedBlack(RouletteBetColor.RED))
                .isNotEqualTo(new RedBlack(RouletteBetColor.BLACK));
        assertThat(everySelection().map(Covered::selection).distinct()).hasSize(46);
    }

    // ------------------------------------------------------------------ helpers

    private static Set<Integer> range(int from, int to, java.util.function.IntPredicate filter) {
        return IntStream.rangeClosed(from, to).filter(filter).boxed().collect(Collectors.toSet());
    }

    /** A selection and the numbers it must win on. */
    record Covered(RouletteSelection selection, Set<Integer> numbers) {

        @Override
        public String toString() {
            return selection.toString();
        }
    }
}
