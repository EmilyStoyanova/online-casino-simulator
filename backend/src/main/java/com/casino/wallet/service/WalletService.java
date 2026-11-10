package com.casino.wallet.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.casino.wallet.config.WalletProperties;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.entity.TransactionType;
import com.casino.wallet.entity.Wallet;
import com.casino.wallet.entity.WalletTransaction;
import com.casino.wallet.exception.BalanceLimitExceededException;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
import com.casino.wallet.exception.InsufficientFundsException;
import com.casino.wallet.exception.WalletBusyException;
import com.casino.wallet.exception.WalletNotFoundException;
import com.casino.wallet.repository.WalletRepository;
import com.casino.wallet.repository.WalletTransactionRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the {@code wallet} module: balances and their changes (ADR-0003,
 * {@code docs/plans/week-03.md} §4, §7). Internal only: no HTTP operation credits or debits a wallet;
 * other modules call {@link #credit} and {@link #debit} (ArchUnit R13 limits who may).
 *
 * <p>Every change runs in one transaction (joining the caller's, so a game round can debit, credit and
 * store its round atomically) in this order:
 * <ol>
 *   <li>validate the operation itself (type and direction, amount, key, reference);</li>
 *   <li>lock the wallet row, waiting at most {@code app.wallet.lock-timeout};</li>
 *   <li>look up the idempotency key: the same operation again returns the original result, other
 *       values with the same key are rejected;</li>
 *   <li>check the business rules (insufficient funds, the 2^53 - 1 limit, overflow);</li>
 *   <li>append exactly one ledger entry and set the balance; both commit together.</li>
 * </ol>
 * The idempotency lookup happens after the lock, so concurrent duplicates are serialized by the lock
 * (the unique constraint stays as the last line of defence). A failed operation writes nothing.
 * Exceptions carry no amounts, balances, keys or ids of the operation.
 *
 * <p>The starting balance is the only {@code STARTING_BALANCE} entry of a wallet and is credited only
 * by {@link #openWallet}, never through {@link #credit}.
 */
@Service
public class WalletService {

    /** Same pattern as {@code ck_wallet_transactions_key}. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[a-z0-9][a-z0-9:._-]{0,99}$");
    private static final int MAX_REFERENCE_LENGTH = 100;
    /** Idempotency key of the starting entry; the same in V7 for wallets of existing accounts. */
    static final String STARTING_BALANCE_KEY = "starting-balance";

    private final WalletRepository wallets;
    private final WalletTransactionRepository entries;
    private final WalletProperties properties;

    public WalletService(WalletRepository wallets, WalletTransactionRepository entries, WalletProperties properties) {
        this.wallets = wallets;
        this.entries = entries;
        this.properties = properties;
    }

    /**
     * Current balance of a user's wallet, without a lock (the last committed value).
     *
     * @throws WalletNotFoundException if the user has no wallet (a data error; nothing is created)
     */
    @Transactional(readOnly = true)
    public long getBalance(UUID userId) {
        return wallets.findByUserId(userId).orElseThrow(() -> new WalletNotFoundException(userId)).getBalance();
    }

    /**
     * Opens the wallet of a new player: creates it with balance 0 unless it exists, then credits
     * {@code app.wallet.starting-balance} as its first ledger entry ({@code STARTING_BALANCE}, key
     * {@code starting-balance}). Joins the caller's transaction (registration), so the account, the
     * wallet and the entry commit or roll back together.
     *
     * <p>Idempotent: if the wallet already has its starting entry, nothing changes and that entry is
     * returned with {@code replayed = true}, whatever the setting is now (its amount was fixed when the
     * wallet was opened). Only for players; the caller decides that (registration publishes
     * {@code UserRegistered} for players only).
     *
     * @return the starting entry and the balance after it
     * @throws BalanceLimitExceededException if an existing wallet without a starting entry cannot take it
     * @throws IdempotencyKeyReusedException if the key {@code starting-balance} belongs to another entry
     * @throws WalletBusyException           if the wallet stayed locked longer than the lock timeout
     */
    @Transactional
    public WalletOperationResult openWallet(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        wallets.insertIfAbsent(userId);
        Wallet wallet = lock(userId);

        Optional<WalletTransaction> earlier = entries.findByWalletIdAndIdempotencyKey(wallet.getId(), STARTING_BALANCE_KEY);
        if (earlier.isPresent()) {
            if (earlier.get().getType() != TransactionType.STARTING_BALANCE) {
                throw new IdempotencyKeyReusedException();
            }
            return new WalletOperationResult(earlier.get().getId(), earlier.get().getBalanceAfter(), true);
        }
        return append(wallet, TransactionType.STARTING_BALANCE, properties.startingBalance(), STARTING_BALANCE_KEY, null);
    }

    /**
     * Adds {@code operation.amount()} coins.
     *
     * @throws IllegalArgumentException      if the operation is invalid, its type is not a credit type or
     *                                       it is {@code STARTING_BALANCE} (only {@link #openWallet})
     * @throws BalanceLimitExceededException if the balance would exceed 2^53 - 1
     * @throws IdempotencyKeyReusedException if the key belongs to an operation with other values
     * @throws WalletBusyException           if the wallet stayed locked longer than the lock timeout
     * @throws WalletNotFoundException       if the user has no wallet
     */
    @Transactional
    public WalletOperationResult credit(UUID userId, WalletOperation operation) {
        return apply(userId, operation, true);
    }

    /**
     * Removes {@code operation.amount()} coins.
     *
     * @throws IllegalArgumentException      if the operation is invalid or its type is not a debit type
     * @throws InsufficientFundsException    if the amount is larger than the balance
     * @throws IdempotencyKeyReusedException if the key belongs to an operation with other values
     * @throws WalletBusyException           if the wallet stayed locked longer than the lock timeout
     * @throws WalletNotFoundException       if the user has no wallet
     */
    @Transactional
    public WalletOperationResult debit(UUID userId, WalletOperation operation) {
        return apply(userId, operation, false);
    }

    private WalletOperationResult apply(UUID userId, WalletOperation operation, boolean credit) {
        Objects.requireNonNull(userId, "userId");
        validate(operation, credit);
        long signedAmount = credit ? operation.amount() : -operation.amount();

        Wallet wallet = lock(userId);

        Optional<WalletTransaction> earlier = entries.findByWalletIdAndIdempotencyKey(wallet.getId(), operation.idempotencyKey());
        if (earlier.isPresent()) {
            return replay(earlier.get(), operation, signedAmount);
        }

        return append(wallet, TransactionType.valueOf(operation.type().name()), signedAmount,
                operation.idempotencyKey(), operation.reference());
    }

    /** Checks the business rules, then appends one entry and sets the balance (the wallet is locked). */
    private WalletOperationResult append(Wallet wallet, TransactionType type, long signedAmount, String key, String reference) {
        long newBalance = newBalance(wallet.getBalance(), signedAmount);
        WalletTransaction entry = entries.save(new WalletTransaction(wallet.getId(), type, signedAmount, newBalance, key, reference));
        wallet.changeBalanceTo(newBalance);
        return new WalletOperationResult(entry.getId(), newBalance, false);
    }

    /** Programming errors of the caller: nothing is locked or written. */
    private static void validate(WalletOperation operation, boolean credit) {
        Objects.requireNonNull(operation, "operation");
        WalletTransactionType type = Objects.requireNonNull(operation.type(), "type");
        if (type == WalletTransactionType.STARTING_BALANCE) {
            throw new IllegalArgumentException("The starting balance is credited only when a wallet is opened");
        }
        if (type.isCredit() != credit) {
            throw new IllegalArgumentException(type + " is a " + (type.isCredit() ? "credit" : "debit") + " type");
        }
        if (operation.amount() <= 0) {
            throw new IllegalArgumentException("The amount must be positive");
        }
        if (operation.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(operation.idempotencyKey()).matches()) {
            throw new IllegalArgumentException("The idempotency key must match " + IDEMPOTENCY_KEY.pattern());
        }
        if (operation.reference() != null && (operation.reference().isEmpty() || operation.reference().length() > MAX_REFERENCE_LENGTH)) {
            throw new IllegalArgumentException("The reference must have 1 to " + MAX_REFERENCE_LENGTH + " characters");
        }
    }

    private Wallet lock(UUID userId) {
        try {
            return wallets.findByUserIdForUpdate(userId, properties.lockTimeout())
                    .orElseThrow(() -> new WalletNotFoundException(userId));
        } catch (PessimisticLockingFailureException ex) {
            // PostgreSQL cancelled the lock wait (lock_timeout); the transaction is aborted and rolls back.
            throw new WalletBusyException();
        }
    }

    private static WalletOperationResult replay(WalletTransaction earlier, WalletOperation operation, long signedAmount) {
        boolean sameOperation = earlier.getType().name().equals(operation.type().name())
                && earlier.getAmount() == signedAmount
                && Objects.equals(earlier.getReference(), operation.reference());
        if (!sameOperation) {
            throw new IdempotencyKeyReusedException();
        }
        return new WalletOperationResult(earlier.getId(), earlier.getBalanceAfter(), true);
    }

    /** Integer arithmetic only; overflow and the limit are the same outcome for a credit. */
    private static long newBalance(long balance, long signedAmount) {
        long result;
        try {
            result = Math.addExact(balance, signedAmount);
        } catch (ArithmeticException ex) {
            throw signedAmount > 0 ? new BalanceLimitExceededException() : new InsufficientFundsException();
        }
        if (result < 0) {
            throw new InsufficientFundsException();
        }
        if (result > Wallet.MAX_BALANCE) {
            throw new BalanceLimitExceededException();
        }
        return result;
    }
}
