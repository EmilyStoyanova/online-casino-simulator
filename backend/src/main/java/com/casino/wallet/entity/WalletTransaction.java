package com.casino.wallet.entity;

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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

/**
 * One entry of the append-only wallet ledger ({@code wallet_transactions}, migration V6).
 *
 * <p>Entries are never changed or deleted: this class has no setters and is {@link Immutable}, so
 * Hibernate never issues an {@code UPDATE} for it, and the database rejects every {@code UPDATE} and
 * {@code DELETE} (trigger {@code trg_wallet_transactions_append_only}). {@code amount} is signed
 * (credit &gt; 0, debit &lt; 0, never 0); {@code balanceAfter} is the wallet's balance right after this
 * entry. The idempotency key is unique per wallet and is never exposed by the API.
 *
 * <p>The id comes from an identity column and increases in commit order per wallet, because entries
 * are only inserted while the wallet row is locked. Ids can have gaps (rolled-back inserts).
 */
@Entity
@Immutable
@Table(name = "wallet_transactions")
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private TransactionType type;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private long balanceAfter;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(updatable = false, length = 100)
    private String reference;

    @Generated(event = EventType.INSERT)
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** For JPA. */
    protected WalletTransaction() {
    }

    /**
     * @param walletId       the wallet the entry belongs to
     * @param type           reason; its direction must match the sign of {@code amount}
     * @param amount         signed change in coins, never 0
     * @param balanceAfter   the wallet's balance after this entry
     * @param idempotencyKey key of the operation, unique per wallet
     * @param reference      optional id of the cause (e.g. a game round, Week 4), or {@code null}
     */
    public WalletTransaction(UUID walletId, TransactionType type, long amount, long balanceAfter,
                             String idempotencyKey, String reference) {
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.type = Objects.requireNonNull(type, "type");
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.reference = reference;
    }

    public Long getId() {
        return id;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public TransactionType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
