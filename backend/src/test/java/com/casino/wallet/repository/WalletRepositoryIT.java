package com.casino.wallet.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.wallet.entity.TransactionType;
import com.casino.wallet.entity.Wallet;
import com.casino.wallet.entity.WalletTransaction;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link WalletRepository} and {@link WalletTransactionRepository} against PostgreSQL (Testcontainers)
 * with the Flyway schema (V6): mapping, constraints seen through JPA, the append-only ledger,
 * rollbacks, keyset history and the row lock. Constraint and trigger details on PostgreSQL 17 and 18:
 * {@code MigrationCompatibilityIT}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WalletRepositoryIT {

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private WalletTransactionRepository entries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void resetDatabase() {
        // Ledger rows cannot be deleted (trigger); only the guarded TRUNCATE empties the tables.
        testDatabase.reset();
    }

    // ------------------------------------------------------------------ wallets

    @Test
    void aNewWalletIsEmptyAndGetsItsTimestampsFromTheDatabase() {
        UUID user = user("alice@example.com");

        Wallet saved = transactions.execute(status -> wallets.save(new Wallet(user)));

        Wallet found = wallets.findByUserId(user).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId()).isNotNull();
        assertThat(found.getUserId()).isEqualTo(user);
        assertThat(found.getBalance()).isZero();
        assertThat(found.getCreatedAt()).isNotNull().isEqualTo(found.getUpdatedAt());
        assertThat(wallets.findByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aUserHasAtMostOneWalletAndAWalletNeedsAnExistingUser() {
        UUID user = user("bob@example.com");
        transactions.executeWithoutResult(status -> wallets.save(new Wallet(user)));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> wallets.save(new Wallet(user))))
                .satisfies(error -> assertThat(constraintOf(error)).isEqualTo("uq_wallets_user_id"));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> wallets.save(new Wallet(UUID.randomUUID()))))
                .satisfies(error -> assertThat(constraintOf(error)).isEqualTo("fk_wallets_user"));
        assertThat(count("wallets")).isOne();
    }

    @Test
    void anAccountWithAWalletCannotBeDeleted() {
        UUID user = user("carol@example.com");
        transactions.executeWithoutResult(status -> wallets.save(new Wallet(user)));

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_wallets_user");
        assertThat(count("users")).isOne();
    }

    // ------------------------------------------------------------------ ledger

    @Test
    void entriesAreStoredWithAnIdentityIdAndFoundByTheirIdempotencyKey() {
        Wallet wallet = wallet("dave@example.com");

        WalletTransaction saved = transactions.execute(status -> entries.save(entry(wallet, 1000, 1000, "starting-balance")));

        assertThat(saved.getId()).isPositive();
        WalletTransaction found = entries.findByWalletIdAndIdempotencyKey(wallet.getId(), "starting-balance").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getWalletId()).isEqualTo(wallet.getId());
        assertThat(found.getType()).isEqualTo(TransactionType.STARTING_BALANCE);
        assertThat(found.getAmount()).isEqualTo(1000);
        assertThat(found.getBalanceAfter()).isEqualTo(1000);
        assertThat(found.getReference()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(entries.findByWalletIdAndIdempotencyKey(wallet.getId(), "other-key")).isEmpty();
    }

    @Test
    void anIdempotencyKeyIsUniquePerWalletOnly() {
        Wallet first = wallet("erin@example.com");
        Wallet second = wallet("frank@example.com");
        transactions.executeWithoutResult(status -> entries.save(entry(first, 1000, 1000, "starting-balance")));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> entries.save(entry(first, 5, 1005, "starting-balance"))))
                .satisfies(error -> assertThat(constraintOf(error)).isEqualTo("uq_wallet_transactions_idempotency"));
        // The same key on another wallet is independent.
        transactions.executeWithoutResult(status -> entries.save(entry(second, 1000, 1000, "starting-balance")));
        assertThat(count("wallet_transactions")).isEqualTo(2);
    }

    @Test
    void entriesCanNeitherBeChangedNorDeleted() {
        Wallet wallet = wallet("grace@example.com");
        WalletTransaction saved = transactions.execute(status -> entries.save(entry(wallet, 1000, 1000, "starting-balance")));

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE wallet_transactions SET amount = 5 WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("wallet_transactions is append-only: UPDATE rejected");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM wallet_transactions WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("wallet_transactions is append-only: DELETE rejected");
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM wallet_transactions WHERE id = ?", Long.class, saved.getId()))
                .isEqualTo(1000);
        // The repositories offer no way to change or remove rows either.
        assertThat(methodNames(WalletTransactionRepository.class)).noneMatch(name -> name.startsWith("delete") || name.startsWith("update"));
        assertThat(methodNames(WalletRepository.class)).noneMatch(name -> name.startsWith("delete"));
    }

    @Test
    void aRolledBackEntryLeavesNothingButItsIdIsNotReused() {
        Wallet wallet = wallet("heidi@example.com");
        AtomicLong rolledBack = new AtomicLong();

        transactions.executeWithoutResult(status -> {
            rolledBack.set(entries.save(entry(wallet, 1000, 1000, "starting-balance")).getId());
            status.setRollbackOnly();
        });

        assertThat(count("wallet_transactions")).isZero();
        assertThat(entries.findByWalletIdAndIdempotencyKey(wallet.getId(), "starting-balance")).isEmpty();
        // Ids can have gaps: never a sign of a deleted entry.
        WalletTransaction next = transactions.execute(status -> entries.save(entry(wallet, 1000, 1000, "starting-balance")));
        assertThat(next.getId()).isGreaterThan(rolledBack.get());
    }

    @Test
    void historyIsNewestFirstAndPagedByIdForOneWalletOnly() {
        Wallet wallet = wallet("ivan@example.com");
        Wallet other = wallet("judy@example.com");
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            long amount = i;
            String key = "entry-" + i;
            ids.add(transactions.execute(status -> entries.save(entry(wallet, amount, amount, key))).getId());
            transactions.executeWithoutResult(status -> entries.save(entry(other, amount, amount, key)));
        }

        List<WalletTransaction> first = entries.findByWalletIdOrderByIdDesc(wallet.getId(), Limit.of(2));
        List<WalletTransaction> second = entries.findByWalletIdAndIdLessThanOrderByIdDesc(wallet.getId(), first.getLast().getId(), Limit.of(2));
        List<WalletTransaction> last = entries.findByWalletIdAndIdLessThanOrderByIdDesc(wallet.getId(), second.getLast().getId(), Limit.of(2));

        assertThat(first).extracting(WalletTransaction::getId).containsExactly(ids.get(4), ids.get(3));
        assertThat(second).extracting(WalletTransaction::getId).containsExactly(ids.get(2), ids.get(1));
        assertThat(last).extracting(WalletTransaction::getId).containsExactly(ids.get(0));
        assertThat(entries.findByWalletIdAndIdLessThanOrderByIdDesc(wallet.getId(), ids.get(0), Limit.of(2))).isEmpty();
        assertThat(first).extracting(WalletTransaction::getWalletId).containsOnly(wallet.getId());
    }

    // ------------------------------------------------------------------ row lock

    @Test
    void theRowLockSerializesWritersButNotReadersAndTheWaiterSeesTheCommittedBalance() throws Exception {
        UUID user = user("mallory@example.com");
        transactions.executeWithoutResult(status -> wallets.save(new Wallet(user)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                wallets.findByUserIdForUpdate(user, LOCK_TIMEOUT).orElseThrow();
                jdbcTemplate.update("UPDATE wallets SET balance = 700 WHERE user_id = ?", user);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Long> waiter = executor.submit(() -> transactions.execute(status ->
                    wallets.findByUserIdForUpdate(user, LOCK_TIMEOUT).orElseThrow().getBalance()));
            // The second writer waits for the lock …
            assertThatThrownBy(() -> waiter.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            // … while a plain read does not, and sees the last committed balance.
            Future<Long> reader = executor.submit(() -> wallets.findByUserId(user).orElseThrow().getBalance());
            assertThat(reader.get(5, TimeUnit.SECONDS)).isZero();

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            // After the commit, the waiter gets the lock and reads the committed balance (READ COMMITTED).
            assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo(700);
        }
    }

    @Test
    void lockingRequiresATransaction() {
        UUID user = user("niaj@example.com");
        transactions.executeWithoutResult(status -> wallets.save(new Wallet(user)));

        // A lock outside a transaction would end immediately; JPA refuses it.
        assertThatThrownBy(() -> wallets.findByUserIdForUpdate(user, LOCK_TIMEOUT)).isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------------ helpers

    private UUID user(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at)
                VALUES (?, '{noop}not-used', 'Player', now()) RETURNING id""", UUID.class, email);
    }

    private Wallet wallet(String email) {
        UUID user = user(email);
        return transactions.execute(status -> wallets.save(new Wallet(user)));
    }

    private static WalletTransaction entry(Wallet wallet, long amount, long balanceAfter, String key) {
        return new WalletTransaction(wallet.getId(), TransactionType.STARTING_BALANCE, amount, balanceAfter, key, null);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getMethods()).map(Method::getName).toList();
    }

    /** Name of the violated constraint, as Hibernate reports it. */
    private static String constraintOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not release the lock");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
