package com.casino.wallet.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.casino.wallet.entity.Wallet;

/**
 * Locking query of {@link WalletRepository}, implemented with the {@code EntityManager}
 * ({@link WalletLockingImpl}) because the lock timeout is configuration, which an annotation such as
 * {@code @QueryHints} cannot take.
 */
public interface WalletLocking {

    /**
     * The wallet of a user, locked for the rest of the transaction. Every balance change goes through
     * this method: a second caller waits until the first commits or rolls back, and then reads the
     * latest committed balance (PostgreSQL {@code READ COMMITTED}). Requires an active transaction. An
     * operation locks at most one wallet (deadlock rule, {@code docs/plans/week-03.md} §4).
     *
     * <p>Hibernate 7 on PostgreSQL issues {@code SELECT … FOR NO KEY UPDATE}: it excludes every other
     * writer of the row, but not the key-share locks of foreign-key checks, so ledger inserts that
     * reference the wallet are not blocked. The wait is bounded: Hibernate sets
     * {@code SET LOCAL lock_timeout} for the statement and restores the previous value afterwards
     * (verified in W3-04, {@code WalletLockTimeoutIT}).
     *
     * @param lockTimeout longest wait for the lock; then the statement fails with a
     *                    {@code PessimisticLockingFailureException} and the transaction must roll back
     */
    Optional<Wallet> findByUserIdForUpdate(UUID userId, Duration lockTimeout);
}
