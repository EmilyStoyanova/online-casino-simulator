package com.casino.game.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.casino.game.model.RouletteWager;
import com.casino.game.service.RouletteService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/games/roulette/spins} (contract schema {@code RouletteSpinRequest}): the bets
 * and nothing else. Properties that are not listed (e.g. {@code winningNumber}, {@code payout},
 * {@code balance}, {@code userId}) are ignored and never reach the round.
 *
 * @param bets 1–10 bets, no selection twice
 */
@Schema(name = "RouletteSpinRequest")
@DistinctSelections
public record RouletteSpinRequest(
        @Schema(requiredMode = REQUIRED)
        @NotNull(message = "must not be null")
        @Size(min = 1, max = RouletteService.MAX_BETS)
        List<@NotNull(message = "must not be null") @Valid RouletteBetRequest> bets) {

    public RouletteSpinRequest {
        bets = bets == null ? null : Collections.unmodifiableList(new ArrayList<>(bets));
    }

    /** The validated bets as domain values, in request order. */
    public List<RouletteWager> toWagers() {
        return bets.stream().map(RouletteBetRequest::toWager).toList();
    }
}
