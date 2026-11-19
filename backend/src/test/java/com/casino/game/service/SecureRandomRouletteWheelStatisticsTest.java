package com.casino.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * <b>Diagnostic, not a CI gate</b> (Q9, {@code docs/plans/week-04.md} §10.5): a Pearson chi-square check of the
 * real {@link SecureRandomRouletteWheel}. It is skipped in every normal build, because a probabilistic test
 * can fail by chance; correctness is proven deterministically by {@code RouletteRulesTest} and
 * {@code SecureRandomRouletteWheelTest}. Run it on purpose, e.g. before a release:
 *
 * <pre>./mvnw test -Dtest=SecureRandomRouletteWheelStatisticsTest -Droulette.statistics=true</pre>
 *
 * It checks the integration with {@code SecureRandom} (a wrong bound, a missing number, a modulo bias),
 * not the quality of the JDK's generator.
 */
@EnabledIfSystemProperty(named = "roulette.statistics", matches = "true")
class SecureRandomRouletteWheelStatisticsTest {

    private static final int DRAWS = 370_000;

    /**
     * Upper 10^-6 quantile of the chi-square distribution with 36 degrees of freedom (computed from the
     * closed form of the upper tail for an even number of degrees of freedom; Wilson–Hilferty gives ≈ 92).
     * A fair wheel exceeds it once in a million runs.
     */
    private static final double CRITICAL_VALUE = 91.502;

    @Test
    void theNumbersAreUniformlyDistributed() {
        SecureRandomRouletteWheel wheel = new SecureRandomRouletteWheel();
        long[] counts = new long[37];
        for (int i = 0; i < DRAWS; i++) {
            counts[wheel.spin()]++;
        }

        double expected = DRAWS / 37.0;
        double chiSquare = 0;
        for (long count : counts) {
            chiSquare += (count - expected) * (count - expected) / expected;
        }
        System.out.printf("Roulette wheel: %d draws, chi-square %.3f (36 degrees of freedom, critical value %.3f "
                + "at 10^-6; mean under fairness 36)%n", DRAWS, chiSquare, CRITICAL_VALUE);

        assertThat(counts).doesNotContain(0L);
        assertThat(chiSquare).isLessThan(CRITICAL_VALUE);
    }
}
