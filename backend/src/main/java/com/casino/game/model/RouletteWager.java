package com.casino.game.model;

import java.util.Objects;

/**
 * A selection with its stake in whole virtual coins. Only the structural rule (a positive amount) is
 * checked here; the API limits (1–1,000 coins, 1–10 bets, no selection twice) belong to the request
 * validation (W4-07).
 *
 * @param selection what the bet is placed on
 * @param amount    stake in whole coins, positive
 */
public record RouletteWager(RouletteSelection selection, long amount) {

    public RouletteWager {
        Objects.requireNonNull(selection, "selection");
        if (amount <= 0) {
            throw new IllegalArgumentException("The amount of a bet must be positive");
        }
    }
}
