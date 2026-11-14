package archfixture.wallet.service;

import archfixture.user.dto.UserDto;
import archfixture.user.service.UserService;

/** Violates R14: the wallet may use user.dto (allowed here), but not user.service. */
public class AccountManagementFromWallet {

    public String owner(UserService users, UserDto event) {
        return users.find().name() + event.name();
    }
}
