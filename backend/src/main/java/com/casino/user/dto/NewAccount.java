package com.casino.user.dto;

/**
 * Data for a new player account, passed by registration to {@code UserAccountService}.
 *
 * @param email          as entered; normalised by {@code UserAccountService}
 * @param passwordHash   encoded hash from the {@code PasswordEncoder}, never the password
 * @param displayName    public name, already trimmed
 * @param adultConfirmed the user's explicit confirmation of being at least 18; must be {@code true}
 */
public record NewAccount(String email, String passwordHash, String displayName, boolean adultConfirmed) {

    /** Never shows the hash. */
    @Override
    public String toString() {
        return "NewAccount[displayName=" + displayName + ", adultConfirmed=" + adultConfirmed + "]";
    }
}
