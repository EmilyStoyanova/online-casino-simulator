package com.casino.game.model;

import java.util.Objects;

/**
 * A bet of a played round with its payout.
 *
 * @param selection what the bet was placed on
 * @param amount    stake in whole coins
 * @param payout    coins credited back for it: stake plus net winnings on a win, 0 on a loss
 */
public record SettledWager(RouletteSelection selection, long amount, long payout) {

    public SettledWager {
        Objects.requireNonNull(selection, "selection");
    }
}
