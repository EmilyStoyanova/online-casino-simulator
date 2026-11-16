package com.casino.game.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A played roulette round as stored (the result of {@code RouletteService.spin}, mapped to the contract's
 * {@code RouletteSpinResult} in W4-07). A new round and a replay of it are equal, because both are built
 * from the stored round.
 *
 * @param roundId       server-generated round id
 * @param winningNumber the number drawn, 0–36
 * @param winningColor  its color
 * @param bets          the bets with their payouts, in the order of the original request
 * @param totalStake    sum of the amounts (the {@code ROULETTE_STAKE} entry)
 * @param totalPayout   sum of the payouts (the {@code ROULETTE_PAYOUT} entry, if positive)
 * @param balance       the wallet balance right after the round
 * @param createdAt     when the round was played (microsecond precision)
 */
public record PlayedRound(UUID roundId, int winningNumber, RouletteNumberColor winningColor, List<SettledWager> bets,
                          long totalStake, long totalPayout, long balance, Instant createdAt) {

    public PlayedRound {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(winningColor, "winningColor");
        bets = List.copyOf(bets);
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
