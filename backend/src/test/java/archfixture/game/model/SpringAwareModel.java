package archfixture.game.model;

import org.springframework.util.StringUtils;

/** Violates R17: a model type depending on Spring. */
public class SpringAwareModel {

    boolean named(String name) {
        return StringUtils.hasText(name);
    }
}
