package com.casino.wallet.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * The virtual-coin wallet of a player ({@code wallets}, migration V6). One per user
 * ({@code uq_wallets_user_id}); the owner never changes (trigger {@code trg_wallets_owner_fixed}).
 *
 * <p>The balance is whole coins between 0 and {@link #MAX_BALANCE}, also enforced by
 * {@code ck_wallets_balance}. It changes only together with a ledger entry, while the row is locked
 * ({@code WalletRepository.findByUserIdForUpdate}); there is no optimistic version. The user is
 * referenced by id only: entities of other modules are never mapped (ArchUnit R3).
 * {@code createdAt} and {@code updatedAt} are set by the database (default and trigger).
 *
 * <p>Never returned by controllers; services map it to DTOs.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    /** 2^53 - 1: the largest balance, so every value is exact in JavaScript clients. */
    public static final long MAX_BALANCE = 9_007_199_254_740_991L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private long balance;

    @Generated(event = EventType.INSERT)
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** For JPA. */
    protected Wallet() {
    }

    /**
     * An empty wallet (balance 0). The starting balance is credited as the first ledger entry, so the
     * balance always equals the sum of the entries.
     *
     * @param userId id of the player who owns the wallet
     */
    public Wallet(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    /**
     * Sets the balance after a ledger entry. The business rules (insufficient funds, limit, overflow)
     * are checked by {@code WalletService} before; this check only keeps the entity from ever holding a
     * value the database would reject.
     *
     * @throws IllegalArgumentException if {@code newBalance} is outside 0 … {@link #MAX_BALANCE}
     */
    public void changeBalanceTo(long newBalance) {
        if (newBalance < 0 || newBalance > MAX_BALANCE) {
            throw new IllegalArgumentException("A balance must be between 0 and " + MAX_BALANCE);
        }
        this.balance = newBalance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
