package com.casino.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import com.casino.game.model.RouletteRules;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.RouletteSettlement;
import com.casino.game.model.RouletteWager;
import org.junit.jupiter.api.Test;

/**
 * The production wheel (W4-04, AC-W4-04.4). Deterministic: the delegation to {@code SecureRandom.nextInt(37)}
 * is proven with a controlled {@code SecureRandom}; the real generator is only checked for its bounds. Its
 * distribution is a separate, opt-in diagnostic ({@link SecureRandomRouletteWheelStatisticsTest}).
 */
class SecureRandomRouletteWheelTest {

    @Test
    void theWheelReturnsSecureRandomNextIntOfThirtySevenUnchanged() {
        RecordingSecureRandom random = new RecordingSecureRandom(0, 36, 17);
        SecureRandomRouletteWheel wheel = new SecureRandomRouletteWheel(random);

        assertThat(List.of(wheel.spin(), wheel.spin(), wheel.spin())).containsExactly(0, 36, 17);
        // Every call asks for a bound of 37: no modulo mapping of a wider range.
        assertThat(random.bounds).containsExactly(37, 37, 37);
    }

    @Test
    void theRealGeneratorStaysWithinZeroToThirtySix() {
        SecureRandomRouletteWheel wheel = new SecureRandomRouletteWheel();
        boolean[] seen = new boolean[37];
        for (int i = 0; i < 100_000; i++) {
            int number = wheel.spin();
            assertThat(number).isBetween(0, 36);
            seen[number] = true;
        }
        // Not a statistical test: with 100,000 draws the chance that any number never appears is below 10^-1000.
        for (int n = 0; n <= 36; n++) {
            assertThat(seen[n]).as("number %d drawn", n).isTrue();
        }
    }

    @Test
    void aWheelNeedsASecureRandom() {
        assertThatNullPointerException().isThrownBy(() -> new SecureRandomRouletteWheel(null)).withMessage("random");
    }

    @Test
    void testsChooseTheWinningNumberThroughTheWheelInterface() {
        // How W4-06 tests will fix the result: any RouletteWheel, e.g. a lambda; production has only the
        // SecureRandom wheel (CasinoSimulatorApplicationIT checks the bean).
        RouletteWheel fixed = () -> 17;

        RouletteSettlement settlement = RouletteRules.settle(List.of(new RouletteWager(new Straight(17), 10)), fixed.spin());

        assertThat(settlement.totalPayout()).isEqualTo(360);
    }

    /** A {@code SecureRandom} that returns the given values and records the requested bounds. */
    private static final class RecordingSecureRandom extends SecureRandom {

        private final int[] values;
        private final List<Integer> bounds = new ArrayList<>();
        private int next;

        RecordingSecureRandom(int... values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            bounds.add(bound);
            return values[next++];
        }
    }
}
