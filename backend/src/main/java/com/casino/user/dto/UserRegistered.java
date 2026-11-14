package com.casino.user.dto;

import java.util.UUID;

/**
 * Application event published when a player account has been created, within the creating transaction.
 * Synchronous listeners join that transaction: the wallet module opens the player's wallet with the
 * starting balance ({@code wallet.service.WalletOpener}); a failure there rolls the registration back.
 * Not published for the administrator bootstrap.
 *
 * @param userId id of the new account
 */
public record UserRegistered(UUID userId) {
}
