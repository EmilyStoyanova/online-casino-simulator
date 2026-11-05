package com.casino.user.dto;

import java.util.UUID;

/**
 * Application event published when an account has been created, within the creating transaction.
 * The wallet module will grant the starting balance on it (Week 3); there is no listener yet.
 *
 * @param userId id of the new account
 */
public record UserRegistered(UUID userId) {
}
