package archfixture.game.service;

import java.security.SecureRandom;

/** Violates R16: a SecureRandom seeded by the application is predictable. */
public class PredictableSecureRandom {

    int seededAtConstruction() {
        return new SecureRandom(new byte[] {1, 2, 3}).nextInt(37);
    }

    int reseeded() {
        SecureRandom random = new SecureRandom();
        random.setSeed(42L);
        return random.nextInt(37);
    }
}
