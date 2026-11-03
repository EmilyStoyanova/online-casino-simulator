package com.casino.user.repository;

import java.util.Optional;
import java.util.UUID;

import com.casino.user.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

/** Database access to user accounts ({@code users}). Used only by the {@code user} services. */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** @param email normalised email (trimmed, lower-cased), as stored */
    Optional<UserAccount> findByEmail(String email);

    /** @param email normalised email (trimmed, lower-cased), as stored */
    boolean existsByEmail(String email);
}
