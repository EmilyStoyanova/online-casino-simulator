package archfixture.game.service;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** Violates R16: weak or time-seeded randomness. */
public class WeakRandomness {

    int fromRandom() {
        return new Random().nextInt(37);
    }

    int seededWithTheClock() {
        return new Random(System.currentTimeMillis()).nextInt(37);
    }

    int fromMath() {
        return (int) (Math.random() * 37);
    }

    int fromThreadLocalRandom() {
        return ThreadLocalRandom.current().nextInt(37);
    }
}
