package com.casino.wallet.repository;

import java.util.Optional;
import java.util.UUID;

import com.casino.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Database access to wallets ({@code wallets}). Used only by the {@code wallet} services.
 *
 * <p>Exposes only what the wallet needs (no {@code delete…}): a wallet is never removed, and its
 * ledger references it ({@code ON DELETE RESTRICT}). The locking query comes from {@link WalletLocking}.
 */
public interface WalletRepository extends Repository<Wallet, UUID>, WalletLocking {

    /** Inserts an empty wallet unless the user already has one ({@code uq_wallets_user_id}). */
    String INSERT_IF_ABSENT = "INSERT INTO wallets (user_id, balance) VALUES (:userId, 0) ON CONFLICT (user_id) DO NOTHING";

    Wallet save(Wallet wallet);

    /**
     * Creates an empty wallet (balance 0) for the user if none exists; never fails because of an existing
     * one, also when another transaction inserts it at the same moment.
     *
     * @return 1 if a wallet was inserted, 0 if the user already had one
     */
    @Modifying(flushAutomatically = true)
    @Query(value = INSERT_IF_ABSENT, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId);

    /** The wallet of a user, without a lock: for reads (balance, history). */
    Optional<Wallet> findByUserId(UUID userId);
}
