package com.casino.wallet.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.UUID;

import com.casino.wallet.dto.WalletTransactionType;
import org.junit.jupiter.api.Test;

/** {@link Wallet} bounds and the alignment of the stored and the public transaction types. */
class WalletModelTest {

    @Test
    void aWalletStartsEmptyAndItsBalanceStaysWithinZeroAndTwoToThe53MinusOne() {
        Wallet wallet = new Wallet(UUID.randomUUID());
        assertThat(wallet.getBalance()).isZero();
        assertThat(Wallet.MAX_BALANCE).isEqualTo((1L << 53) - 1);

        wallet.changeBalanceTo(Wallet.MAX_BALANCE);
        assertThat(wallet.getBalance()).isEqualTo(Wallet.MAX_BALANCE);
        wallet.changeBalanceTo(0);
        assertThat(wallet.getBalance()).isZero();

        assertThatIllegalArgumentException().isThrownBy(() -> wallet.changeBalanceTo(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> wallet.changeBalanceTo(Wallet.MAX_BALANCE + 1));
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void theStoredAndThePublicTransactionTypesHaveTheSameValuesAndDirections() {
        assertThat(Arrays.stream(TransactionType.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(WalletTransactionType.values()).map(Enum::name).toList());
        for (WalletTransactionType type : WalletTransactionType.values()) {
            assertThat(TransactionType.valueOf(type.name()).isCredit()).as(type.name()).isEqualTo(type.isCredit());
        }
        assertThat(WalletTransactionType.STARTING_BALANCE.isCredit()).isTrue();
        assertThat(WalletTransactionType.TEST_CREDIT.isCredit()).isTrue();
        assertThat(WalletTransactionType.TEST_DEBIT.isCredit()).isFalse();
    }
}
