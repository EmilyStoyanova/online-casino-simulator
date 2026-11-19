package com.casino.game.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Option of an {@link RouletteBetCategory#EVEN_ODD} bet (contract schema {@code RouletteParity}). Zero is neither. */
@Schema(name = "RouletteParity", enumAsRef = true)
public enum RouletteParity {
    EVEN,
    ODD
}
