package com.casino.game.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The five bet categories of European roulette (contract schema {@code RouletteBetCategory}), each with
 * its standard odds. Odds are <b>net winnings</b>: coins won per coin staked, in addition to the
 * returned stake. The <b>payout</b> of a winning bet is the stake plus the net winnings, so
 * {@link #payoutMultiplier()} = {@link #netOdds()} + 1; a losing bet pays nothing.
 */
@Schema(name = "RouletteBetCategory", enumAsRef = true)
public enum RouletteBetCategory {

    /** One number 0–36: 35:1, payout 36 × the amount. */
    STRAIGHT(35),

    /** Red or black (18 numbers each): 1:1, payout 2 × the amount. */
    RED_BLACK(1),

    /** Even or odd (18 numbers each; zero is neither): 1:1, payout 2 × the amount. */
    EVEN_ODD(1),

    /** Low 1–18 or high 19–36: 1:1, payout 2 × the amount. */
    LOW_HIGH(1),

    /** First 1–12, second 13–24 or third 25–36: 2:1, payout 3 × the amount. */
    DOZEN(2);

    private final int netOdds;

    RouletteBetCategory(int netOdds) {
        this.netOdds = netOdds;
    }

    /** Net winnings per coin staked on a winning bet, the stake not included (35, 1 or 2). */
    public int netOdds() {
        return netOdds;
    }

    /** Coins credited back per coin staked on a winning bet, the stake included (36, 2 or 3). */
    public int payoutMultiplier() {
        return netOdds + 1;
    }
}
