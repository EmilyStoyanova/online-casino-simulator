package com.casino.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserAccountTest {

    private static final Instant CONFIRMED = Instant.parse("2026-11-02T09:15:00Z");
    private static final String HASH = "{bcrypt}$2a$12$abcdefghijklmnopqrstuuJ0b0t3Zl0Q1zU0z9eXv7m8e3a1b2c3d4";

    @Test
    void newAccountIsAnActivePlayerWithTheGivenData() {
        UserAccount account = new UserAccount("alice@example.com", HASH, "Alice", CONFIRMED);

        assertThat(account.getRole()).isEqualTo(Role.PLAYER);
        assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(account.getEmail()).isEqualTo("alice@example.com");
        assertThat(account.getPasswordHash()).isEqualTo(HASH);
        assertThat(account.getDisplayName()).isEqualTo("Alice");
        assertThat(account.getAdultConfirmedAt()).isEqualTo(CONFIRMED);
        assertThat(account.getLastLoginAt()).isNull();
        // Assigned when it is saved: id by Hibernate, timestamps by the database.
        assertThat(account.getId()).isNull();
        assertThat(account.getCreatedAt()).isNull();
        assertThat(account.getUpdatedAt()).isNull();
        assertThat(account.getVersion()).isZero();
    }

    @Test
    void requiresAllAccountData() {
        assertThatNullPointerException().isThrownBy(() -> new UserAccount(null, HASH, "Alice", CONFIRMED)).withMessage("email");
        assertThatNullPointerException().isThrownBy(() -> new UserAccount("a@b.c", null, "Alice", CONFIRMED))
                .withMessage("passwordHash");
        assertThatNullPointerException().isThrownBy(() -> new UserAccount("a@b.c", HASH, null, CONFIRMED))
                .withMessage("displayName");
        assertThatNullPointerException().isThrownBy(() -> new UserAccount("a@b.c", HASH, "Alice", null))
                .withMessage("adultConfirmedAt");
    }
}
