package com.casino.game.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.casino.game.model.RouletteBetCategory;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteRange;
import com.casino.game.model.RouletteSelection;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.RouletteWager;
import com.casino.game.service.RouletteService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * One bet of a spin request (contract schema {@code RouletteBet}): a category, the one option property of
 * that category ({@link OneOptionPerCategory}) and a whole-coin amount of 1–1,000. {@code amount} and
 * {@code number} accept JSON integer tokens only, the enum fields exact value names as JSON strings
 * ({@link StrictJson}, Q10); anything else is {@code 400 MALFORMED_REQUEST}, values outside a range {@code 400 VALIDATION_FAILED}.
 *
 * @param category the bet category
 * @param number   option of {@code STRAIGHT}: 0–36
 * @param color    option of {@code RED_BLACK}
 * @param parity   option of {@code EVEN_ODD}
 * @param range    option of {@code LOW_HIGH}
 * @param dozen    option of {@code DOZEN}
 * @param amount   stake in whole coins, 1–1,000
 */
@Schema(name = "RouletteBet")
@OneOptionPerCategory
public record RouletteBetRequest(
        @Schema(requiredMode = REQUIRED)
        @NotNull(message = "must not be null")
        @JsonDeserialize(using = StrictJson.EnumName.class)
        RouletteBetCategory category,

        @Min(0)
        @Max(36)
        @JsonDeserialize(using = StrictJson.OptionalInt.class)
        Integer number,

        @JsonDeserialize(using = StrictJson.EnumName.class)
        RouletteBetColor color,

        @JsonDeserialize(using = StrictJson.EnumName.class)
        RouletteParity parity,

        @JsonDeserialize(using = StrictJson.EnumName.class)
        RouletteRange range,

        @JsonDeserialize(using = StrictJson.EnumName.class)
        RouletteDozen dozen,

        @Schema(requiredMode = REQUIRED)
        @NotNull(message = "must not be null")
        @Min(1)
        @Max(RouletteService.MAX_AMOUNT)
        @JsonDeserialize(using = StrictJson.RequiredLong.class)
        Long amount) {

    /** The validated bet as a domain value (only called after validation). */
    public RouletteWager toWager() {
        RouletteSelection selection = switch (category) {
            case STRAIGHT -> new Straight(number);
            case RED_BLACK -> new RedBlack(color);
            case EVEN_ODD -> new EvenOdd(parity);
            case LOW_HIGH -> new LowHigh(range);
            case DOZEN -> new Dozen(dozen);
        };
        return new RouletteWager(selection, amount);
    }
}
