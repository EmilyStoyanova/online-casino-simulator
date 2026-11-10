package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;

/** Business rules and the order of steps of {@link WalletService}, with the repositories mocked. */
class WalletServiceTest {

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final long STARTING_BALANCE = 1000;
    private static final UUID USER = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");
    private static final UUID WALLET = UUID.fromString("7d7e6c1a-1111-4c55-9d8e-2f6c1a7e9b10");

    private final WalletRepository wallets = mock(WalletRepository.class);
    private final WalletTransactionRepository entries = mock(WalletTransactionRepository.class);
    private final WalletService service = new WalletService(wallets, entries, new WalletProperties(LOCK_TIMEOUT, STARTING_BALANCE));
    private final AtomicLong ids = new AtomicLong(100);
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        wallet = walletWithBalance(1000);
        when(wallets.findByUserIdForUpdate(USER, LOCK_TIMEOUT)).thenReturn(Optional.of(wallet));
        when(wallets.findByUserId(USER)).thenReturn(Optional.of(wallet));
        when(entries.findByWalletIdAndIdempotencyKey(eq(WALLET), anyString())).thenReturn(Optional.empty());
        when(entries.save(any(WalletTransaction.class))).thenAnswer(invocation -> {
            WalletTransaction entry = invocation.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", ids.incrementAndGet());
            return entry;
        });
    }

    // ------------------------------------------------------------------ credit and debit

    @Test
    void creditAddsCoinsWithOneLedgerEntryInTheDocumentedOrder() {
        WalletOperationResult result = service.credit(USER, credit(250, "credit-1"));

        assertThat(result).isEqualTo(new WalletOperationResult(101, 1250, false));
        assertThat(wallet.getBalance()).isEqualTo(1250);
        InOrder order = inOrder(wallets, entries);
        order.verify(wallets).findByUserIdForUpdate(USER, LOCK_TIMEOUT);
        order.verify(entries).findByWalletIdAndIdempotencyKey(WALLET, "credit-1");
        order.verify(entries).save(any(WalletTransaction.class));
        WalletTransaction entry = savedEntry();
        assertThat(entry.getWalletId()).isEqualTo(WALLET);
        assertThat(entry.getType()).isEqualTo(TransactionType.TEST_CREDIT);
        assertThat(entry.getAmount()).isEqualTo(250);
        assertThat(entry.getBalanceAfter()).isEqualTo(1250);
        assertThat(entry.getIdempotencyKey()).isEqualTo("credit-1");
        assertThat(entry.getReference()).isNull();
    }

    @Test
    void debitRemovesCoinsAndStoresANegativeAmount() {
        WalletOperationResult result = service.debit(USER, new WalletOperation(WalletTransactionType.TEST_DEBIT, 300, "debit-1", "round-7"));

        assertThat(result.balanceAfter()).isEqualTo(700);
        assertThat(wallet.getBalance()).isEqualTo(700);
        assertThat(savedEntry().getAmount()).isEqualTo(-300);
        assertThat(savedEntry().getReference()).isEqualTo("round-7");
    }

    @Test
    void theWholeBalanceCanBeDebitedButNotMore() {
        assertThat(service.debit(USER, debit(1000, "all")).balanceAfter()).isZero();

        assertThatThrownBy(() -> service.debit(USER, debit(1, "one-more"))).isInstanceOf(InsufficientFundsException.class);
        assertThat(wallet.getBalance()).isZero();
        verify(entries, times(1)).save(any());
    }

    @Test
    void insufficientFundsChangeNothing() {
        assertThatThrownBy(() -> service.debit(USER, debit(1001, "too-much"))).isInstanceOf(InsufficientFundsException.class);

        assertThat(wallet.getBalance()).isEqualTo(1000);
        verify(entries, never()).save(any());
    }

    @Test
    void theBalanceLimitIsTwoToThePowerOf53MinusOne() {
        wallet.changeBalanceTo(Wallet.MAX_BALANCE - 10);

        assertThat(service.credit(USER, credit(10, "to-the-limit")).balanceAfter()).isEqualTo(9_007_199_254_740_991L);
        assertThatThrownBy(() -> service.credit(USER, credit(1, "beyond"))).isInstanceOf(BalanceLimitExceededException.class);
        assertThat(wallet.getBalance()).isEqualTo(Wallet.MAX_BALANCE);
    }

    @Test
    void integerOverflowIsRejectedWithoutChanges() {
        // Long.MAX_VALUE would overflow any positive balance; a credit becomes BALANCE_LIMIT_EXCEEDED.
        assertThatThrownBy(() -> service.credit(USER, credit(Long.MAX_VALUE, "overflow"))).isInstanceOf(BalanceLimitExceededException.class);
        assertThatThrownBy(() -> service.debit(USER, debit(Long.MAX_VALUE, "huge-debit"))).isInstanceOf(InsufficientFundsException.class);
        assertThatThrownBy(() -> service.credit(USER, credit(Wallet.MAX_BALANCE, "max-credit")))
                .isInstanceOf(BalanceLimitExceededException.class);

        assertThat(wallet.getBalance()).isEqualTo(1000);
        verify(entries, never()).save(any());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void theSameOperationAgainReturnsTheOriginalResultAndChangesNothing() {
        WalletTransaction earlier = entry(77, TransactionType.TEST_DEBIT, -300, 700, "debit-1", "round-7");
        when(entries.findByWalletIdAndIdempotencyKey(WALLET, "debit-1")).thenReturn(Optional.of(earlier));

        WalletOperationResult replay = service.debit(USER, new WalletOperation(WalletTransactionType.TEST_DEBIT, 300, "debit-1", "round-7"));

        assertThat(replay).isEqualTo(new WalletOperationResult(77, 700, true));
        assertThat(wallet.getBalance()).isEqualTo(1000);
        verify(entries, never()).save(any());
        // The replay is still serialized by the lock.
        verify(wallets).findByUserIdForUpdate(USER, LOCK_TIMEOUT);
    }

    @Test
    void theSameKeyWithOtherValuesIsRejected() {
        WalletTransaction earlier = entry(77, TransactionType.TEST_DEBIT, -300, 700, "op-1", "round-7");
        when(entries.findByWalletIdAndIdempotencyKey(WALLET, "op-1")).thenReturn(Optional.of(earlier));

        // Other amount, other reference, no reference, other type/direction.
        assertThatThrownBy(() -> service.debit(USER, new WalletOperation(WalletTransactionType.TEST_DEBIT, 301, "op-1", "round-7")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> service.debit(USER, new WalletOperation(WalletTransactionType.TEST_DEBIT, 300, "op-1", "round-8")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> service.debit(USER, new WalletOperation(WalletTransactionType.TEST_DEBIT, 300, "op-1", null)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> service.credit(USER, new WalletOperation(WalletTransactionType.TEST_CREDIT, 300, "op-1", "round-7")))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(wallet.getBalance()).isEqualTo(1000);
        verify(entries, never()).save(any());
    }

    @Test
    void aReplayIsAnsweredEvenWhenTheOriginalWouldNowFail() {
        // The original debit already happened; replaying it must not be judged against today's balance.
        wallet.changeBalanceTo(0);
        when(entries.findByWalletIdAndIdempotencyKey(WALLET, "debit-1"))
                .thenReturn(Optional.of(entry(77, TransactionType.TEST_DEBIT, -1000, 0, "debit-1", null)));

        assertThat(service.debit(USER, debit(1000, "debit-1")).replayed()).isTrue();
    }

    // ------------------------------------------------------------------ validation (nothing locked)

    @Test
    void invalidOperationsAreRejectedBeforeAnythingIsLockedOrWritten() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.credit(USER, credit(0, "zero")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.debit(USER, debit(-5, "negative")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.debit(USER, credit(5, "credit-type-as-debit")))
                .withMessage("TEST_CREDIT is a credit type");
        assertThatIllegalArgumentException().isThrownBy(() -> service.credit(USER, debit(5, "debit-type-as-credit")))
                .withMessage("TEST_DEBIT is a debit type");
        for (String key : new String[] {null, "", "Upper", "with space", "-dash-first", "x".repeat(101)}) {
            assertThatIllegalArgumentException().as(String.valueOf(key)).isThrownBy(() -> service.credit(USER, credit(5, key)));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.credit(USER, new WalletOperation(WalletTransactionType.TEST_CREDIT, 5, "ok", "")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.credit(USER, new WalletOperation(WalletTransactionType.TEST_CREDIT, 5, "ok", "r".repeat(101))));
        assertThatThrownBy(() -> service.credit(USER, new WalletOperation(null, 5, "ok", null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.credit(USER, null)).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(wallets, entries);
    }

    @Test
    void theLongestKeyAndReferenceAreAccepted() {
        String key = "a" + "b:._-".repeat(19) + "1234";

        service.credit(USER, new WalletOperation(WalletTransactionType.TEST_CREDIT, 5, key, "r".repeat(100)));

        assertThat(savedEntry().getIdempotencyKey()).hasSize(100);
    }

    // ------------------------------------------------------------------ lock and missing wallet

    @Test
    void aLockTimeoutBecomesWalletBusyWithoutChanges() {
        when(wallets.findByUserIdForUpdate(USER, LOCK_TIMEOUT)).thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThatThrownBy(() -> service.debit(USER, debit(5, "busy")))
                .isInstanceOf(WalletBusyException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ProblemCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(entries);
    }

    @Test
    void aMissingWalletIsADataErrorAndNothingIsCreated() {
        UUID stranger = UUID.randomUUID();
        when(wallets.findByUserIdForUpdate(stranger, LOCK_TIMEOUT)).thenReturn(Optional.empty());
        when(wallets.findByUserId(stranger)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.credit(stranger, credit(5, "x"))).isInstanceOf(WalletNotFoundException.class)
                .hasMessage("No wallet for user " + stranger);
        assertThatThrownBy(() -> service.getBalance(stranger)).isInstanceOf(WalletNotFoundException.class);
        verify(wallets, never()).save(any());
        verifyNoInteractions(entries);
    }

    @Test
    void theBalanceIsReadWithoutALock() {
        assertThat(service.getBalance(USER)).isEqualTo(1000);
        verify(wallets, never()).findByUserIdForUpdate(any(), any());
    }

    // ------------------------------------------------------------------ opening a wallet (W3-05)

    @Test
    void openingAWalletInsertsItThenCreditsTheStartingBalanceUnderTheLock() {
        wallet.changeBalanceTo(0);

        WalletOperationResult result = service.openWallet(USER);

        assertThat(result).isEqualTo(new WalletOperationResult(101, STARTING_BALANCE, false));
        assertThat(wallet.getBalance()).isEqualTo(STARTING_BALANCE);
        InOrder order = inOrder(wallets, entries);
        order.verify(wallets).insertIfAbsent(USER);
        order.verify(wallets).findByUserIdForUpdate(USER, LOCK_TIMEOUT);
        order.verify(entries).findByWalletIdAndIdempotencyKey(WALLET, "starting-balance");
        order.verify(entries).save(any(WalletTransaction.class));
        WalletTransaction entry = savedEntry();
        assertThat(entry.getType()).isEqualTo(TransactionType.STARTING_BALANCE);
        assertThat(entry.getAmount()).isEqualTo(STARTING_BALANCE);
        assertThat(entry.getBalanceAfter()).isEqualTo(STARTING_BALANCE);
        assertThat(entry.getIdempotencyKey()).isEqualTo("starting-balance");
        assertThat(entry.getReference()).isNull();
    }

    @Test
    void theStartingBalanceComesFromTheConfiguration() {
        WalletService configured = new WalletService(wallets, entries, new WalletProperties(LOCK_TIMEOUT, 250));
        wallet.changeBalanceTo(0);

        assertThat(configured.openWallet(USER).balanceAfter()).isEqualTo(250);
        assertThat(savedEntry().getAmount()).isEqualTo(250);
    }

    @Test
    void openingAnOpenedWalletAgainChangesNothingWhateverTheSettingIsNow() {
        // Opened earlier with 500 coins (another setting); the wallet has moved on since.
        wallet.changeBalanceTo(80);
        when(entries.findByWalletIdAndIdempotencyKey(WALLET, "starting-balance"))
                .thenReturn(Optional.of(entry(5, TransactionType.STARTING_BALANCE, 500, 500, "starting-balance", null)));

        WalletOperationResult again = service.openWallet(USER);

        assertThat(again).isEqualTo(new WalletOperationResult(5, 500, true));
        assertThat(wallet.getBalance()).isEqualTo(80);
        verify(entries, never()).save(any());
    }

    @Test
    void theStartingKeyHeldByAnotherEntryIsRejected() {
        when(entries.findByWalletIdAndIdempotencyKey(WALLET, "starting-balance"))
                .thenReturn(Optional.of(entry(5, TransactionType.TEST_CREDIT, 500, 500, "starting-balance", null)));

        assertThatThrownBy(() -> service.openWallet(USER)).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(entries, never()).save(any());
    }

    @Test
    void theStartingBalanceCannotBeCreditedOutsideOpeningAWallet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.credit(USER, new WalletOperation(WalletTransactionType.STARTING_BALANCE, 1000, "starting-balance", null)))
                .withMessage("The starting balance is credited only when a wallet is opened");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.debit(USER, new WalletOperation(WalletTransactionType.STARTING_BALANCE, 1000, "x", null)));
        verifyNoInteractions(wallets, entries);
    }

    @Test
    void aWalletBusyWhileOpeningIsReportedAsSuch() {
        when(wallets.findByUserIdForUpdate(USER, LOCK_TIMEOUT)).thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThatThrownBy(() -> service.openWallet(USER)).isInstanceOf(WalletBusyException.class);
        verifyNoInteractions(entries);
    }

    // ------------------------------------------------------------------ helpers

    private static WalletOperation credit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_CREDIT, amount, key, null);
    }

    private static WalletOperation debit(long amount, String key) {
        return new WalletOperation(WalletTransactionType.TEST_DEBIT, amount, key, null);
    }

    private static Wallet walletWithBalance(long balance) {
        Wallet wallet = new Wallet(USER);
        ReflectionTestUtils.setField(wallet, "id", WALLET);
        wallet.changeBalanceTo(balance);
        return wallet;
    }

    private static WalletTransaction entry(long id, TransactionType type, long amount, long balanceAfter, String key, String reference) {
        WalletTransaction entry = new WalletTransaction(WALLET, type, amount, balanceAfter, key, reference);
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }

    private WalletTransaction savedEntry() {
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(entries, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
