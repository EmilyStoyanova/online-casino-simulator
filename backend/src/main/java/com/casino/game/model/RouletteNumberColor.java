package com.casino.game.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Color of a number on the wheel (contract schema {@code RouletteNumberColor}): {@code GREEN} only for 0. */
@Schema(name = "RouletteNumberColor", enumAsRef = true)
public enum RouletteNumberColor {
    RED,
    BLACK,
    GREEN
}
