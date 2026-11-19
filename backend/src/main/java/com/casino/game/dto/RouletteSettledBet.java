package com.casino.game.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.casino.game.model.RouletteBetCategory;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteRange;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.SettledWager;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A bet of a played round (contract schema {@code RouletteSettledBet}): the selection as requested (the
 * category and its one option property; the other option properties are omitted from the JSON), the
 * amount and the payout (stake plus net winnings on a win, 0 on a loss).
 */
@Schema(name = "RouletteSettledBet")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouletteSettledBet(
        @Schema(requiredMode = REQUIRED) RouletteBetCategory category,
        Integer number,
        RouletteBetColor color,
        RouletteParity parity,
        RouletteRange range,
        RouletteDozen dozen,
        @Schema(requiredMode = REQUIRED) long amount,
        @Schema(requiredMode = REQUIRED) long payout) {

    static RouletteSettledBet from(SettledWager wager) {
        return switch (wager.selection()) {
            case Straight straight -> new RouletteSettledBet(straight.category(), straight.number(), null, null, null, null,
                    wager.amount(), wager.payout());
            case RedBlack redBlack -> new RouletteSettledBet(redBlack.category(), null, redBlack.color(), null, null, null,
                    wager.amount(), wager.payout());
            case EvenOdd evenOdd -> new RouletteSettledBet(evenOdd.category(), null, null, evenOdd.parity(), null, null,
                    wager.amount(), wager.payout());
            case LowHigh lowHigh -> new RouletteSettledBet(lowHigh.category(), null, null, null, lowHigh.range(), null,
                    wager.amount(), wager.payout());
            case Dozen dozen -> new RouletteSettledBet(dozen.category(), null, null, null, null, dozen.dozen(),
                    wager.amount(), wager.payout());
        };
    }
}
