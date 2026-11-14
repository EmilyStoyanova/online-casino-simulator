package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.entity.Wallet;
import com.casino.wallet.exception.BalanceLimitExceededException;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
import com.casino.wallet.exception.InsufficientFundsException;
import com.casino.wallet.exception.WalletNotFoundException;
import com.casino.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link WalletService} against PostgreSQL with the real schema (plus the test-only types
 * {@code TEST_CREDIT}/{@code TEST_DEBIT}): balance and ledger change together, failures change nothing,
 * idempotency on real rows, rollback of the caller's transaction, and the ledger stays consistent.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WalletServiceIT {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private WalletTestData data;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        data = new WalletTestData(jdbcTemplate, wallets, walletService, transactions);
    }

    @AfterEach
    void resetDatabase() {
        testDatabase.reset();
    }

    @Test
    void creditAndDebitChangeTheBalanceWithExactlyOneEntryEach() {
        UUID player = data.playerWithBalance("alice@example.com", 1000);

        WalletOperationResult credit = walletService.credit(player, credit(250, "credit-1"));
        WalletOperationResult debit = walletService.debit(player, new WalletOperation(WalletTransactionType.TEST_DEBIT, 400, "debit-1", "round-1"));

        assertThat(credit.balanceAfter()).isEqualTo(1250);
        assertThat(debit.balanceAfter()).isEqualTo(850);
        assertThat(debit.transactionId()).isGreaterThan(credit.transactionId());
        assertThat(credit.replayed()).isFalse();
        assertThat(walletService.getBalance(player)).isEqualTo(850);
        assertThat(data.balance(player)).isEqualTo(850);
        assertThat(data.entries(player)).isEqualTo(3);
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT * FROM wallet_transactions WHERE id = ?", debit.transactionId());
        assertThat(entry).containsEntry("type", "TEST_DEBIT").containsEntry("amount", -400L).containsEntry("balance_after", 850L)
                .containsEntry("idempotency_key", "debit-1").containsEntry("reference", "round-1");
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void theWholeBalanceCanBeSpentButFailedDebitsChangeNothing() {
        UUID player = data.playerWithBalance("bob@example.com", 500);

        assertThatThrownBy(() -> walletService.debit(player, debit(501, "too-much"))).isInstanceOf(InsufficientFundsException.class);
        assertThat(walletService.debit(player, debit(500, "all")).balanceAfter()).isZero();
        assertThatThrownBy(() -> walletService.debit(player, debit(1, "one-more"))).isInstanceOf(InsufficientFundsException.class);

        assertThat(data.balance(player)).isZero();
        assertThat(data.entries(player)).isEqualTo(2);
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void theBalanceStopsAtTwoToThe53MinusOne() {
        UUID player = data.playerWithBalance("carol@example.com", Wallet.MAX_BALANCE - 1);

        assertThat(walletService.credit(player, credit(1, "last-coin")).balanceAfter()).isEqualTo(Wallet.MAX_BALANCE);
        assertThatThrownBy(() -> walletService.credit(player, credit(1, "beyond"))).isInstanceOf(BalanceLimitExceededException.class);
        assertThatThrownBy(() -> walletService.credit(player, credit(Long.MAX_VALUE, "overflow")))
                .isInstanceOf(BalanceLimitExceededException.class);

        assertThat(data.balance(player)).isEqualTo(9_007_199_254_740_991L);
        assertThat(data.entries(player)).isEqualTo(2);
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void invalidOperationsWriteNothing() {
        UUID player = data.playerWithBalance("dave@example.com", 100);

        assertThatIllegalArgumentException().isThrownBy(() -> walletService.credit(player, credit(0, "zero")));
        assertThatIllegalArgumentException().isThrownBy(() -> walletService.debit(player, debit(-1, "negative")));
        assertThatIllegalArgumentException().isThrownBy(() -> walletService.debit(player, credit(1, "wrong-direction")));
        assertThatIllegalArgumentException().isThrownBy(() -> walletService.credit(player, credit(1, "Not A Key")));

        assertThat(data.balance(player)).isEqualTo(100);
        assertThat(data.entries(player)).isOne();
    }

    @Test
    void repeatingAnOperationReturnsTheOriginalResultAndChangesNothing() {
        UUID player = data.playerWithBalance("erin@example.com", 1000);
        WalletOperationResult first = walletService.debit(player, debit(300, "round-9:stake"));

        WalletOperationResult again = walletService.debit(player, debit(300, "round-9:stake"));
        // Also after the balance changed in between (the original is not judged again).
        walletService.debit(player, debit(700, "spend-the-rest"));
        WalletOperationResult later = walletService.debit(player, debit(300, "round-9:stake"));

        assertThat(again).isEqualTo(new WalletOperationResult(first.transactionId(), 700, true));
        assertThat(later).isEqualTo(again);
        assertThat(data.balance(player)).isZero();
        assertThat(data.entries(player)).isEqualTo(3);
    }

    @Test
    void reusingAKeyWithOtherValuesIsRejectedAndKeysArePerWallet() {
        UUID player = data.playerWithBalance("frank@example.com", 1000);
        UUID other = data.playerWithBalance("grace@example.com", 1000);
        walletService.debit(player, debit(300, "op-1"));

        assertThatThrownBy(() -> walletService.debit(player, debit(301, "op-1"))).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> walletService.credit(player, credit(300, "op-1"))).isInstanceOf(IdempotencyKeyReusedException.class);
        // The same key on another wallet is a different operation.
        assertThat(walletService.debit(other, debit(300, "op-1")).replayed()).isFalse();

        assertThat(data.balance(player)).isEqualTo(700);
        assertThat(data.balance(other)).isEqualTo(700);
        assertThat(data.entries(player)).isEqualTo(2);
    }

    @Test
    void aRolledBackCallerTransactionUndoesBalanceAndEntry() {
        UUID player = data.playerWithBalance("heidi@example.com", 1000);

        // As a Week 4 round would: the wallet change joins the caller's transaction, which then fails.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            walletService.debit(player, debit(400, "round-1:stake"));
            throw new IllegalStateException("the round failed after the stake");
        })).hasMessage("the round failed after the stake");

        assertThat(data.balance(player)).isEqualTo(1000);
        assertThat(data.entries(player)).isOne();
        // The key is free again: the operation never happened.
        assertThat(walletService.debit(player, debit(400, "round-1:stake")).replayed()).isFalse();
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void severalOperationsInOneCallerTransactionCommitTogether() {
        UUID player = data.playerWithBalance("ivan@example.com", 1000);

        WalletOperationResult payout = transactions.execute(status -> {
            walletService.debit(player, debit(100, "round-2:stake"));
            return walletService.credit(player, credit(350, "round-2:payout"));
        });

        assertThat(payout.balanceAfter()).isEqualTo(1250);
        assertThat(data.balance(player)).isEqualTo(1250);
        assertThat(data.entries(player)).isEqualTo(3);
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void aPlayerWithoutAWalletIsADataErrorAndGetsNone() {
        UUID player = data.player("judy@example.com");

        assertThatThrownBy(() -> walletService.credit(player, credit(5, "x"))).isInstanceOf(WalletNotFoundException.class);
        assertThatThrownBy(() -> walletService.getBalance(player)).isInstanceOf(WalletNotFoundException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallets", Long.class)).isZero();
    }

    @Test
    void theTestOnlyTypesAreTheOnlyAdditionToTheProductionTypeRules() {
        UUID player = data.playerWithBalance("mallory@example.com", 10);
        UUID walletId = wallets.findByUserId(player).orElseThrow().getId();

        // The test callback keeps the direction rules: a TEST_DEBIT must be negative, a TEST_CREDIT positive.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wallet_transactions (wallet_id, type, amount, balance_after, idempotency_key)
                VALUES (?, 'TEST_DEBIT', 5, 15, 'positive-debit')""", walletId)).hasMessageContaining("ck_wallet_transactions_test_direction");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wallet_transactions (wallet_id, type, amount, balance_after, idempotency_key)
                VALUES (?, 'ADMIN_GIFT', 5, 15, 'unknown')""", walletId)).hasMessageContaining("ck_wallet_transactions_type");
    }

    private static WalletOperation credit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_CREDIT, amount, key, null);
    }

    private static WalletOperation debit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_DEBIT, amount, key, null);
    }
}
