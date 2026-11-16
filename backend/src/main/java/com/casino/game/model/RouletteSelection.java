package com.casino.game.model;

import java.util.Objects;

/**
 * What a bet is placed on: a {@link RouletteBetCategory} together with its one option. One record per
 * category, so a selection without its option, or with the option of another category, cannot be
 * created (the flat API object of {@code docs/plans/week-04.md} §4.2 is mapped onto these in W4-07).
 * Records compare by value, so two equal selections are the same selection.
 */
public sealed interface RouletteSelection {

    /** The category of this selection. */
    RouletteBetCategory category();

    /** A straight bet on {@code number} (0–36). */
    record Straight(int number) implements RouletteSelection {

        public Straight {
            RouletteRules.requireNumber(number);
        }

        @Override
        public RouletteBetCategory category() {
            return RouletteBetCategory.STRAIGHT;
        }
    }

    /** A bet on the red or the black numbers. */
    record RedBlack(RouletteBetColor color) implements RouletteSelection {

        public RedBlack {
            Objects.requireNonNull(color, "color");
        }

        @Override
        public RouletteBetCategory category() {
            return RouletteBetCategory.RED_BLACK;
        }
    }

    /** A bet on the even or the odd numbers. */
    record EvenOdd(RouletteParity parity) implements RouletteSelection {

        public EvenOdd {
            Objects.requireNonNull(parity, "parity");
        }

        @Override
        public RouletteBetCategory category() {
            return RouletteBetCategory.EVEN_ODD;
        }
    }

    /** A bet on the low (1–18) or the high (19–36) numbers. */
    record LowHigh(RouletteRange range) implements RouletteSelection {

        public LowHigh {
            Objects.requireNonNull(range, "range");
        }

        @Override
        public RouletteBetCategory category() {
            return RouletteBetCategory.LOW_HIGH;
        }
    }

    /** A bet on one dozen. */
    record Dozen(RouletteDozen dozen) implements RouletteSelection {

        public Dozen {
            Objects.requireNonNull(dozen, "dozen");
        }

        @Override
        public RouletteBetCategory category() {
            return RouletteBetCategory.DOZEN;
        }
    }
}
