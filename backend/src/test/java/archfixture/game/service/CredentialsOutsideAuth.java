package archfixture.game.service;

import archfixture.user.dto.UserCredentials;

/** Violates R8. */
public class CredentialsOutsideAuth {

    public String read(UserCredentials credentials) {
        return credentials.passwordHash();
    }
}
