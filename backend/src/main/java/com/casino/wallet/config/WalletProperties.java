package com.casino.wallet.config;

import java.time.Duration;

import com.casino.wallet.entity.Wallet;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Wallet settings ({@code app.wallet.*}). An invalid value stops the start-up.
 *
 * @param lockTimeout     longest wait for a wallet's row lock before a balance change fails without
 *                        changes (5 s by default, 100 ms to 1 min). Bounds the time a request can be held
 *                        by another one that keeps the same wallet locked.
 * @param startingBalance coins credited when a player's wallet is opened (1,000 by default, 1 to
 *                        2^53 - 1, AC-1.7). Copied into the starting entry: a later change affects only
 *                        wallets opened afterwards.
 */
@ConfigurationProperties(prefix = "app.wallet")
@Validated
public record WalletProperties(@DefaultValue("5s") @NotNull @DurationMin(millis = 100) @DurationMax(minutes = 1)
                               Duration lockTimeout,
                               @DefaultValue("1000") @Min(1) @Max(Wallet.MAX_BALANCE)
                               long startingBalance) {
}
