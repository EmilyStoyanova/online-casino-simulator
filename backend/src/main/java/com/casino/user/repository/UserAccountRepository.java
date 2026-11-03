package com.casino.user.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.casino.user.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Database access to user accounts ({@code users}). Used only by the {@code user} services. */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** @param email normalised email (trimmed, lower-cased), as stored */
    Optional<UserAccount> findByEmail(String email);

    /** @param email normalised email (trimmed, lower-cased), as stored */
    boolean existsByEmail(String email);

    /**
     * Sets {@code last_login_at} without incrementing {@code version}: concurrent logins of the same
     * user (e.g. two browsers) must not fail with an optimistic-locking conflict.
     *
     * @return the number of updated rows (0 if the account no longer exists)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UserAccount a SET a.lastLoginAt = :at WHERE a.id = :id")
    int updateLastLoginAt(@Param("id") UUID id, @Param("at") Instant at);
}
