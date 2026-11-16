package archfixture.game.model;

import java.util.Set;

/** Valid for R17: plain Java only. */
public final class PlainRule {

    private static final Set<Integer> RED = Set.of(1, 3, 5);

    private PlainRule() {
    }

    static boolean red(int number) {
        return RED.contains(number);
    }
}
