package com.casino.game.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Option of a {@link RouletteBetCategory#LOW_HIGH} bet (contract schema {@code RouletteRange}). Zero is in neither. */
@Schema(name = "RouletteRange", enumAsRef = true)
public enum RouletteRange {

    /** 1–18. */
    LOW,

    /** 19–36. */
    HIGH
}
