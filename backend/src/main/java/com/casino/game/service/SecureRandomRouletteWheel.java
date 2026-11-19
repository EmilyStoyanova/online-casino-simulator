package com.casino.game.service;

import java.security.SecureRandom;
import java.util.Objects;

import com.casino.game.model.RouletteRules;
import org.springframework.stereotype.Component;

/**
 * The production wheel: {@link SecureRandom#nextInt(int) SecureRandom.nextInt(37)}.
 *
 * <ul>
 *   <li>{@code nextInt(bound)} returns a uniformly distributed value in {@code [0, bound)} by rejection
 *       sampling, so every number 0–36 has probability exactly 1/37: no modulo mapping, no bias.</li>
 *   <li>One {@code new SecureRandom()} (the platform's default, non-blocking algorithm), seeded by the JDK
 *       itself; the application never seeds or reseeds it (ArchUnit R16). Not
 *       {@code SecureRandom.getInstanceStrong()}, which can block on Linux.</li>
 *   <li>{@code SecureRandom} is thread-safe, so one instance serves concurrent rounds.</li>
 * </ul>
 */
@Component
class SecureRandomRouletteWheel implements RouletteWheel {

    private final SecureRandom random;

    SecureRandomRouletteWheel() {
        this(new SecureRandom());
    }

    /** For tests: a {@code SecureRandom} whose output the test controls. */
    SecureRandomRouletteWheel(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public int spin() {
        return random.nextInt(RouletteRules.POCKETS);
    }
}
