package com.casino.user.entity;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A user account ({@code users}, migration V3) with its selected interests ({@code user_interests},
 * migration V5).
 *
 * <p>Holds only the encoded password hash ({@code {bcrypt}$2a$12$…}), never the password. The email
 * must already be normalised (trimmed, lower-cased) by the caller; the database rejects anything
 * else. New accounts are always {@link Role#PLAYER} and {@link UserStatus#ACTIVE}.
 * {@code createdAt} and {@code updatedAt} are set by the database (default and trigger).
 *
 * <p>Only the display name and the interests can change through this class. Every change, also one
 * of the interests only, increments {@code version} (optimistic locking), so overlapping updates of
 * a profile fail instead of overwriting each other. An update writes only the changed columns
 * ({@link DynamicUpdate}): {@code last_login_at} is set by a query that does not increment
 * {@code version}, and a profile update loaded before that query must not write the old value back.
 *
 * <p>Never returned by controllers; services map it to DTOs.
 */
@Entity
@Table(name = "users")
@DynamicUpdate
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

    /**
     * Set by the database at every update (trigger), but read back only at insert: an update that
     * reads generated values ({@code UPDATE … RETURNING}) makes Hibernate report a version conflict
     * as a generic error instead of an optimistic-locking failure. After an update in the same
     * transaction this is the value of the last load.
     */
    @Generated(event = EventType.INSERT)
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /** The primary key of {@code user_interests} prevents duplicates; the limit of 8 is checked by the service. */
    @ManyToMany
    @JoinTable(name = "user_interests",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "interest_code"))
    private Set<Interest> interests = new HashSet<>();

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

    /** The selected interests, including ones that were deactivated after they were selected. */
    public Set<Interest> getInterests() {
        return Collections.unmodifiableSet(interests);
    }

    /** @param displayName new public name, already trimmed and validated */
    public void changeDisplayName(String displayName) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    /**
     * Replaces the whole selection. Interests that stay selected keep their row (and its
     * {@code created_at}); an unchanged selection changes nothing.
     *
     * @param selection the new selection, already validated (active, at most 8)
     */
    public void replaceInterests(Collection<Interest> selection) {
        Set<Interest> wanted = Set.copyOf(selection);
        interests.retainAll(wanted);
        interests.addAll(wanted);
    }
}
