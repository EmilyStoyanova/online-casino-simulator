package com.casino.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.casino.TestcontainersConfiguration;
import com.casino.user.entity.Role;
import com.casino.user.entity.UserAccount;
import com.casino.user.entity.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** {@link UserAccountRepository} against PostgreSQL (Testcontainers) with the Flyway schema (V3). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UserAccountRepositoryIT {

    private static final String PASSWORD = "correct-horse-battery";
    // PostgreSQL stores microseconds.
    private static final Instant CONFIRMED = Instant.parse("2026-11-02T09:15:00.123456Z");

    @Autowired
    private UserAccountRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void deleteAccounts() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void savesAndFindsAnAccount() {
        Instant beforeSave = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UserAccount saved = repository.saveAndFlush(account("alice@example.com"));

        UserAccount byId = repository.findById(saved.getId()).orElseThrow();
        UserAccount byEmail = repository.findByEmail("alice@example.com").orElseThrow();

        assertThat(byEmail.getId()).isEqualTo(byId.getId()).isEqualTo(saved.getId()).isNotNull();
        assertThat(byId.getEmail()).isEqualTo("alice@example.com");
        assertThat(byId.getDisplayName()).isEqualTo("Alice");
        assertThat(byId.getRole()).isEqualTo(Role.PLAYER);
        assertThat(byId.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(byId.getAdultConfirmedAt()).isEqualTo(CONFIRMED);
        assertThat(byId.getLastLoginAt()).isNull();
        assertThat(byId.getVersion()).isZero();
        // Set by the database and read back after the insert.
        assertThat(saved.getCreatedAt()).isNotNull().isAfterOrEqualTo(beforeSave);
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(byId.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void storesOnlyTheEncodedPasswordHash() {
        UserAccount saved = repository.saveAndFlush(account("alice@example.com"));

        String stored = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, saved.getId());

        assertThat(stored).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void emailIsUnique(CapturedOutput output) {
        repository.saveAndFlush(account("alice@example.com"));

        assertThatThrownBy(() -> repository.saveAndFlush(account("alice@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email")
                // Without PostgreSQL's detail "Key (email)=(…)".
                .rootCause().message().contains("uq_users_email").doesNotContain("alice@example.com");
        assertThat(repository.count()).isOne();
        assertThat(output.getAll()).doesNotContain("alice@example.com");
    }

    @Test
    void emailMustBeNormalisedByTheCaller(CapturedOutput output) {
        // Not lower-cased: rejected, so a case variant can never become a second account.
        assertThatThrownBy(() -> repository.saveAndFlush(account("Alice@Example.com")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_email_normalized");
        // PostgreSQL's "Failing row contains (…)" would show the email and the password hash.
        assertThat(output.getAll()).doesNotContain("Alice@Example.com", "{bcrypt}");
        // The lookup is exact; normalisation happens before it.
        repository.saveAndFlush(account("alice@example.com"));
        assertThat(repository.findByEmail("ALICE@example.com")).isEmpty();
    }

    private UserAccount account(String email) {
        return new UserAccount(email, passwordEncoder.encode(PASSWORD), "Alice", CONFIRMED);
    }
}
