package archfixture.game.service;

import java.security.SecureRandom;

/** Valid for R16: an unseeded SecureRandom (a subclass of java.util.Random) is allowed. */
public class SecureWheel {

    private final SecureRandom random = new SecureRandom();

    int spin() {
        return random.nextInt(37);
    }
}
