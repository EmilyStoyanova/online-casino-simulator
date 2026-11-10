package com.casino.wallet.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.casino.wallet.entity.WalletTransaction;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

/**
 * Database access to the append-only ledger ({@code wallet_transactions}). Used only by the
 * {@code wallet} services.
 *
 * <p>Insert and read only: there is no update or delete method, matching the database trigger that
 * rejects both.
 */
public interface WalletTransactionRepository extends Repository<WalletTransaction, Long> {

    /** Inserts a new entry (identity id, so the row is written immediately). */
    WalletTransaction save(WalletTransaction entry);

    /** The entry of an operation, for idempotent replays ({@code uq_wallet_transactions_idempotency}). */
    Optional<WalletTransaction> findByWalletIdAndIdempotencyKey(UUID walletId, String idempotencyKey);

    /** First page of a wallet's history, newest first. */
    List<WalletTransaction> findByWalletIdOrderByIdDesc(UUID walletId, Limit limit);

    /** Next page: entries older than {@code beforeId}, newest first (keyset pagination). */
    List<WalletTransaction> findByWalletIdAndIdLessThanOrderByIdDesc(UUID walletId, long beforeId, Limit limit);
}
