package archfixture.common.exception;

import archfixture.user.service.UserService;

/** Violates R1. */
public class CommonDependsOnModule {

    public Object leak(UserService userService) {
        return userService.find();
    }
}
