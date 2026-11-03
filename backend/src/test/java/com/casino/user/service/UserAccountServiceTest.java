package com.casino.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.casino.user.dto.NewAccount;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRegistered;
import com.casino.user.dto.UserRole;
import com.casino.user.entity.Role;
import com.casino.user.entity.UserAccount;
import com.casino.user.entity.UserStatus;
import com.casino.user.exception.EmailAlreadyRegisteredException;
import com.casino.user.repository.UserAccountRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class UserAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T09:15:00.123456789Z");
    private static final String HASH = "{bcrypt}$2a$12$abcdefghijklmnopqrstuuJ0b0t3Zl0Q1zU0z9eXv7m8e3a1b2c3d4";
    private static final UUID ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");

    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UserAccountService service = new UserAccountService(repository, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void normalisesEmailsByTrimmingAndLowerCasing() {
        assertThat(UserAccountService.normalizeEmail("  Alice@Example.COM\t")).isEqualTo("alice@example.com");
        assertThat(UserAccountService.normalizeEmail("alice@example.com")).isEqualTo("alice@example.com");
    }

    @Test
    void createsAnActivePlayerWithTheNormalisedEmailAndTheServerTime() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> saved(invocation.getArgument(0)));

        UserProfileResponse profile = service.createAccount(new NewAccount(" Alice@Example.com ", HASH, "Alice", true));

        ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
        verify(repository).existsByEmail("alice@example.com");
        verify(repository).saveAndFlush(account.capture());
        assertThat(account.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(account.getValue().getPasswordHash()).isEqualTo(HASH);
        assertThat(account.getValue().getRole()).isEqualTo(Role.PLAYER);
        assertThat(account.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        // AC-1.2: from the clock, in the precision PostgreSQL stores.
        assertThat(account.getValue().getAdultConfirmedAt()).isEqualTo(Instant.parse("2026-11-03T09:15:00.123456Z"));

        assertThat(profile).isEqualTo(new UserProfileResponse(ID, "alice@example.com", "Alice", UserRole.PLAYER, List.of(),
                Instant.parse("2026-11-03T09:15:00.123456Z"), NOW));
        verify(events).publishEvent(new UserRegistered(ID));
    }

    @Test
    void anExistingEmailIsRejectedBeforeSaving() {
        when(repository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createAccount(new NewAccount("ALICE@example.com", HASH, "Alice", true)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void aConcurrentRegistrationOfTheSameEmailIsAConflict() {
        when(repository.saveAndFlush(any())).thenThrow(violation("uq_users_email"));

        assertThatThrownBy(() -> service.createAccount(new NewAccount("alice@example.com", HASH, "Alice", true)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
        verifyNoInteractions(events);
    }

    @Test
    void otherConstraintViolationsAreNotReportedAsConflicts() {
        DataIntegrityViolationException other = violation("ck_users_password_hash_encoded");
        when(repository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.createAccount(new NewAccount("alice@example.com", "plain", "Alice", true)))
                .isSameAs(other);
    }

    @Test
    void anAccountRequiresTheAdultConfirmation() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.createAccount(new NewAccount("alice@example.com", HASH, "Alice", false)));
        verifyNoInteractions(repository, events);
    }

    @Test
    void newAccountNeverShowsTheHash() {
        assertThat(new NewAccount("alice@example.com", HASH, "Alice", true).toString()).doesNotContain(HASH, "bcrypt");
    }

    /** What the repository returns after the insert: the id and database timestamps are set. */
    private static UserAccount saved(UserAccount account) {
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "createdAt", NOW);
        ReflectionTestUtils.setField(account, "updatedAt", NOW);
        return account;
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("violation", null, constraint));
    }
}
