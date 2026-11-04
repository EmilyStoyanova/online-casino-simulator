package com.casino.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void changesTheDisplayName() {
        UserAccount account = new UserAccount("alice@example.com", HASH, "Alice", CONFIRMED);

        account.changeDisplayName("Alice B.");

        assertThat(account.getDisplayName()).isEqualTo("Alice B.");
        assertThatNullPointerException().isThrownBy(() -> account.changeDisplayName(null)).withMessage("displayName");
    }

    @Test
    void replacesTheWholeSelectionOfInterests() {
        UserAccount account = new UserAccount("alice@example.com", HASH, "Alice", CONFIRMED);
        account.replaceInterests(List.of(interest("PROBABILITY"), interest("MATHEMATICS")));

        // Equal by code: another instance of a selected interest stays selected.
        account.replaceInterests(List.of(interest("MATHEMATICS"), interest("STATISTICS")));

        assertThat(account.getInterests()).extracting(Interest::getCode).containsExactlyInAnyOrder("MATHEMATICS", "STATISTICS");
        account.replaceInterests(List.of());
        assertThat(account.getInterests()).isEmpty();
    }

    @Test
    void theSelectionCannotBeChangedFromOutside() {
        UserAccount account = new UserAccount("alice@example.com", HASH, "Alice", CONFIRMED);

        assertThatThrownBy(() -> account.getInterests().add(interest("PROBABILITY")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void interestsAreEqualByCode() {
        assertThat(interest("PROBABILITY")).isEqualTo(interest("PROBABILITY")).hasSameHashCodeAs(interest("PROBABILITY"));
        assertThat(interest("PROBABILITY")).isNotEqualTo(interest("MATHEMATICS")).isNotEqualTo("PROBABILITY");
    }

    private static Interest interest(String code) {
        Interest interest = new Interest();
        ReflectionTestUtils.setField(interest, "code", code);
        return interest;
    }
}
