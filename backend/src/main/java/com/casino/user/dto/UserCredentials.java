package com.casino.user.dto;

import java.util.UUID;

/**
 * What login needs to verify a password. Contains the password hash, so it is used only by the
 * {@code user} and {@code auth} modules (ArchUnit R8) and never returned by the API.
 *
 * @param id           user id, the principal name of the session
 * @param passwordHash encoded hash ({@code {bcrypt}$2a$12$…})
 * @param role         role for the session's authority
 * @param active       {@code false} for a disabled account, which cannot log in
 */
public record UserCredentials(UUID id, String passwordHash, UserRole role, boolean active) {

    /** Never shows the hash. */
    @Override
    public String toString() {
        return "UserCredentials[id=" + id + ", role=" + role + ", active=" + active + "]";
    }
}
