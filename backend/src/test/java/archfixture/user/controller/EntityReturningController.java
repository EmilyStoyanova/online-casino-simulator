package archfixture.user.controller;

import archfixture.user.entity.UserEntity;
import org.springframework.web.bind.annotation.RestController;

/** Violates R6 (and R4): a controller exposes an entity. */
@RestController
public class EntityReturningController {

    public UserEntity get() {
        return new UserEntity();
    }
}
