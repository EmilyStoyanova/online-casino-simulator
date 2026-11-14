package com.casino.wallet.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.casino.wallet.entity.Wallet;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** Spring Data fragment implementation of {@link WalletLocking} (found by its {@code Impl} suffix). */
class WalletLockingImpl implements WalletLocking {

    private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";

    private final EntityManager entityManager;

    WalletLockingImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Wallet> findByUserIdForUpdate(UUID userId, Duration lockTimeout) {
        return entityManager.createQuery("SELECT w FROM Wallet w WHERE w.userId = :userId", Wallet.class)
                .setParameter("userId", userId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint(LOCK_TIMEOUT_HINT, Math.toIntExact(lockTimeout.toMillis()))
                .getResultStream()
                .findFirst();
    }
}
