package com.casino.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;

/**
 * The rules of single-zero European roulette ({@code docs/plans/week-04.md} §4): pure functions, no
 * state, no Spring, no database (ArchUnit R17).
 *
 * <ul>
 *   <li>37 numbers, 0–36. Zero is green, neither even nor odd, neither low nor high, in no dozen: it
 *       wins only a straight bet on 0. No double zero, no <i>la partage</i>, no <i>en prison</i>.</li>
 *   <li>Payout of a winning bet = amount × {@link RouletteBetCategory#payoutMultiplier()} (36, 2 or 3),
 *       i.e. the stake plus the net winnings (35:1, 1:1, 2:1); a losing bet pays 0.</li>
 *   <li>Integer arithmetic only; an overflow throws {@link ArithmeticException} instead of wrapping.</li>
 * </ul>
 *
 * Every bet has the same expected net result, −1/37 of its amount: the house edge is 1/37 ≈ 2.7027 % and
 * the theoretical return to player 36/37 ≈ 97.2973 % ({@code RouletteRulesTest} proves it exactly).
 */
public final class RouletteRules {

    /** Number of pockets on the wheel: the numbers 0–36. */
    public static final int POCKETS = 37;

    /** The highest number on the wheel. */
    public static final int HIGHEST_NUMBER = 36;

    /** The standard red numbers; every other number from 1 to 36 is black. */
    private static final Set<Integer> RED_NUMBERS =
            Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    private RouletteRules() {
    }

    /** The color of {@code number}: green for 0, otherwise red or black. */
    public static RouletteNumberColor colorOf(int number) {
        requireNumber(number);
        if (number == 0) {
            return RouletteNumberColor.GREEN;
        }
        return RED_NUMBERS.contains(number) ? RouletteNumberColor.RED : RouletteNumberColor.BLACK;
    }

    /** Whether {@code selection} wins when {@code winningNumber} is drawn. */
    public static boolean wins(RouletteSelection selection, int winningNumber) {
        Objects.requireNonNull(selection, "selection");
        requireNumber(winningNumber);
        return switch (selection) {
            case Straight straight -> straight.number() == winningNumber;
            case RedBlack redBlack -> colorOf(winningNumber) == switch (redBlack.color()) {
                case RED -> RouletteNumberColor.RED;
                case BLACK -> RouletteNumberColor.BLACK;
            };
            case EvenOdd evenOdd -> winningNumber != 0 && (winningNumber % 2 == 0) == (evenOdd.parity() == RouletteParity.EVEN);
            case LowHigh lowHigh -> winningNumber != 0 && (winningNumber <= 18) == (lowHigh.range() == RouletteRange.LOW);
            case Dozen dozen -> switch (dozen.dozen()) {
                case FIRST -> winningNumber >= 1 && winningNumber <= 12;
                case SECOND -> winningNumber >= 13 && winningNumber <= 24;
                case THIRD -> winningNumber >= 25;
            };
        };
    }

    /**
     * The coins credited back for a bet: {@code amount × payoutMultiplier} on a win (stake included), 0 on a
     * loss. The net result of the bet is {@code payout − amount}.
     *
     * @throws IllegalArgumentException if {@code amount} is not positive or the number is outside 0–36
     * @throws ArithmeticException      if the payout does not fit into a {@code long}
     */
    public static long payout(RouletteSelection selection, long amount, int winningNumber) {
        if (amount <= 0) {
            throw new IllegalArgumentException("The amount of a bet must be positive");
        }
        return wins(selection, winningNumber) ? Math.multiplyExact(amount, selection.category().payoutMultiplier()) : 0;
    }

    /**
     * Evaluates the wagers of one spin: the payout of each (in their order) and the totals.
     *
     * @throws IllegalArgumentException if there is no wager or the number is outside 0–36
     * @throws ArithmeticException      if a payout or a total does not fit into a {@code long}
     */
    public static RouletteSettlement settle(List<RouletteWager> wagers, int winningNumber) {
        Objects.requireNonNull(wagers, "wagers");
        RouletteNumberColor color = colorOf(winningNumber);
        if (wagers.isEmpty()) {
            throw new IllegalArgumentException("A spin needs at least one bet");
        }
        List<Long> payouts = new ArrayList<>(wagers.size());
        long totalStake = 0;
        long totalPayout = 0;
        for (RouletteWager wager : wagers) {
            long payout = payout(wager.selection(), wager.amount(), winningNumber);
            payouts.add(payout);
            totalStake = Math.addExact(totalStake, wager.amount());
            totalPayout = Math.addExact(totalPayout, payout);
        }
        return new RouletteSettlement(winningNumber, color, payouts, totalStake, totalPayout);
    }

    /**
     * Rejects anything but a number on the wheel.
     *
     * @throws IllegalArgumentException if {@code number} is outside 0–36
     */
    static void requireNumber(int number) {
        if (number < 0 || number > HIGHEST_NUMBER) {
            throw new IllegalArgumentException("A roulette number is between 0 and " + HIGHEST_NUMBER);
        }
    }
}
