package archfixture.auth.service;

import archfixture.user.dto.UserCredentials;
import archfixture.user.service.UserService;

/** Uses another module through its public API and may see UserCredentials (R3, R8). */
public class AuthService {

    private final UserService userService;

    public AuthService(UserService userService) {
        this.userService = userService;
    }

    public String check(UserCredentials credentials) {
        return credentials.passwordHash() + userService.find();
    }
}
