package com.casino.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.casino.user.dto.NewAccount;
import com.casino.user.dto.UserCredentials;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRegistered;
import com.casino.user.dto.UserRole;
import com.casino.user.entity.UserAccount;
import com.casino.user.entity.UserStatus;
import com.casino.user.exception.EmailAlreadyRegisteredException;
import com.casino.user.repository.UserAccountRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User accounts: creation (registration), credentials and last login (login), and the normalisation
 * of email addresses.
 *
 * <p>The email is normalised here, once for every caller: trimmed and lower-cased, as the database
 * requires ({@code ck_users_email_normalized}), so case variants can never become a second account.
 */
@Service
public class UserAccountService {

    /** Unique constraint of {@code users.email} (V3). */
    static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";

    private final UserAccountRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UserAccountService(UserAccountRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /** The stored form of an email address: trimmed and lower-cased (locale-independent). */
    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Creates a {@code PLAYER} account in status {@code ACTIVE} and publishes {@link UserRegistered}.
     * The 18+ confirmation time comes from the server clock.
     *
     * @throws EmailAlreadyRegisteredException if the email already has an account, also when another
     *                                         request registers it at the same moment
     * @throws IllegalArgumentException        if the user did not confirm being at least 18
     */
    @Transactional
    public UserProfileResponse createAccount(NewAccount account) {
        if (!account.adultConfirmed()) {
            throw new IllegalArgumentException("An account requires the confirmation of being at least 18");
        }
        String email = normalizeEmail(account.email());
        if (repository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        UserAccount saved;
        try {
            // Flushed here, so a concurrent registration of the same email fails inside this method.
            // Microseconds, as PostgreSQL stores them, so the response shows the stored value.
            Instant adultConfirmedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            saved = repository.saveAndFlush(new UserAccount(email, account.passwordHash(), account.displayName(), adultConfirmedAt));
        } catch (DataIntegrityViolationException ex) {
            if (violates(ex, EMAIL_UNIQUE_CONSTRAINT)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw ex;
        }
        events.publishEvent(new UserRegistered(saved.getId()));
        return profile(saved);
    }

    /**
     * The credentials of the account with this email, for login. The email is normalised first.
     *
     * @return empty if no account has this email
     */
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentials(String email) {
        return repository.findByEmail(normalizeEmail(email)).map(account -> new UserCredentials(account.getId(),
                account.getPasswordHash(), role(account), account.getStatus() == UserStatus.ACTIVE));
    }

    /**
     * Records a successful login ({@code last_login_at}, server clock) and returns the profile.
     *
     * @throws IllegalStateException if the account no longer exists
     */
    @Transactional
    public UserProfileResponse recordLogin(UUID userId) {
        repository.updateLastLoginAt(userId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return profile(repository.findById(userId).orElseThrow(() -> new IllegalStateException("The account no longer exists")));
    }

    private static UserRole role(UserAccount account) {
        return UserRole.valueOf(account.getRole().name());
    }

    private static UserProfileResponse profile(UserAccount account) {
        return new UserProfileResponse(account.getId(), account.getEmail(), account.getDisplayName(),
                role(account), List.of(), account.getAdultConfirmedAt(), account.getCreatedAt());
    }

    private static boolean violates(DataIntegrityViolationException ex, String constraint) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraint.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
