package com.casino.user.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A user account ({@code users}, migration V3).
 *
 * <p>Holds only the encoded password hash ({@code {bcrypt}$2a$12$…}), never the password. The email
 * must already be normalised (trimmed, lower-cased) by the caller; the database rejects anything
 * else. New accounts are always {@link Role#PLAYER} and {@link UserStatus#ACTIVE}.
 * {@code createdAt} and {@code updatedAt} are set by the database (default and trigger).
 *
 * <p>Never returned by controllers; services map it to DTOs.
 */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.PLAYER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(nullable = false)
    private Instant adultConfirmedAt;

    private Instant lastLoginAt;

    @Generated(event = EventType.INSERT)
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /** For JPA. */
    protected UserAccount() {
    }

    /**
     * @param email            normalised email (trimmed, lower-cased)
     * @param passwordHash     encoded hash from the {@code PasswordEncoder}, never the password
     * @param displayName      public name shown in the UI
     * @param adultConfirmedAt when the user confirmed being at least 18 (server clock)
     */
    public UserAccount(String email, String passwordHash, String displayName, Instant adultConfirmedAt) {
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.adultConfirmedAt = Objects.requireNonNull(adultConfirmedAt, "adultConfirmedAt");
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getAdultConfirmedAt() {
        return adultConfirmedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
