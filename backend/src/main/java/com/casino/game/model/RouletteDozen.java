package com.casino.game.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Option of a {@link RouletteBetCategory#DOZEN} bet (contract schema {@code RouletteDozen}). Zero is in none. */
@Schema(name = "RouletteDozen", enumAsRef = true)
public enum RouletteDozen {

    /** 1–12. */
    FIRST,

    /** 13–24. */
    SECOND,

    /** 25–36. */
    THIRD
}
