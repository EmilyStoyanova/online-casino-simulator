package com.casino.game.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.casino.game.model.PlayedRound;
import com.casino.game.model.RouletteNumberColor;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of {@code POST /api/v1/games/roulette/spins} (contract schema {@code RouletteSpinResult}): the
 * stored round, mapped one to one from {@link PlayedRound}. A replay maps the same stored round, so its
 * body is identical (round id, number, bets in request order, totals, balance, timestamp).
 */
@Schema(name = "RouletteSpinResult")
public record RouletteSpinResult(
        @Schema(requiredMode = REQUIRED) UUID roundId,
        @Schema(requiredMode = REQUIRED) int winningNumber,
        @Schema(requiredMode = REQUIRED) RouletteNumberColor winningColor,
        @Schema(requiredMode = REQUIRED) List<RouletteSettledBet> bets,
        @Schema(requiredMode = REQUIRED) long totalStake,
        @Schema(requiredMode = REQUIRED) long totalPayout,
        @Schema(requiredMode = REQUIRED) long balance,
        @Schema(requiredMode = REQUIRED) Instant createdAt) {

    public RouletteSpinResult {
        bets = List.copyOf(bets);
    }

    public static RouletteSpinResult from(PlayedRound round) {
        return new RouletteSpinResult(round.roundId(), round.winningNumber(), round.winningColor(),
                round.bets().stream().map(RouletteSettledBet::from).toList(), round.totalStake(), round.totalPayout(),
                round.balance(), round.createdAt());
    }
}
