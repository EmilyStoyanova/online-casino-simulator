package com.casino.game.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Option of a {@link RouletteBetCategory#RED_BLACK} bet (contract schema {@code RouletteBetColor}). Zero cannot be chosen. */
@Schema(name = "RouletteBetColor", enumAsRef = true)
public enum RouletteBetColor {
    RED,
    BLACK
}
