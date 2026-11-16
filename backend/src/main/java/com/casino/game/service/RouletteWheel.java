package com.casino.game.service;

/**
 * Draws the winning number of a roulette round on the server (ADR-0003). Production has exactly one
 * implementation, {@link SecureRandomRouletteWheel}; tests replace it through the test context (for
 * example with a lambda returning a fixed number), never through production configuration. Nothing
 * from a request reaches it.
 */
@FunctionalInterface
public interface RouletteWheel {

    /** The next winning number, 0–36, each with probability 1/37. */
    int spin();
}
