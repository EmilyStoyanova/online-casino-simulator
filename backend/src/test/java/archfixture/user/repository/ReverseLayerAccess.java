package archfixture.user.repository;

import archfixture.user.service.UserService;

/** Violates R4: a repository calls a service. */
public class ReverseLayerAccess {

    public Object load(UserService userService) {
        return userService.find();
    }
}
