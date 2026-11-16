package com.casino.game.model;

import java.util.List;

/**
 * The evaluated bets of one spin ({@link RouletteRules#settle}).
 *
 * @param winningNumber the number drawn, 0–36
 * @param winningColor  its color
 * @param payouts       the payout of each wager, in the order of the wagers: stake plus net winnings on a
 *                      win, 0 on a loss
 * @param totalStake    sum of the amounts (the stake debited from the wallet)
 * @param totalPayout   sum of the payouts (credited to the wallet if positive)
 */
public record RouletteSettlement(int winningNumber, RouletteNumberColor winningColor, List<Long> payouts,
                                 long totalStake, long totalPayout) {

    public RouletteSettlement {
        payouts = List.copyOf(payouts);
    }

    /** Net change of the balance for this spin: {@code totalPayout − totalStake} (negative for a loss). */
    public long netResult() {
        return Math.subtractExact(totalPayout, totalStake);
    }
}
