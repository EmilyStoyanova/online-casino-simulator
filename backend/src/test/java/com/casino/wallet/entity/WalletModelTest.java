package com.casino.wallet.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.UUID;

import com.casino.wallet.dto.WalletEntryType;
import com.casino.wallet.dto.WalletTransactionType;
import org.junit.jupiter.api.Test;

/** {@link Wallet} bounds and the alignment of the stored, the command and the published transaction types. */
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
        // Exactly the production types of V8 with their directions (W4-03).
        assertThat(Arrays.stream(WalletTransactionType.values()).map(Enum::name))
                .containsExactly("STARTING_BALANCE", "ROULETTE_STAKE", "ROULETTE_PAYOUT");
        assertThat(WalletTransactionType.STARTING_BALANCE.isCredit()).isTrue();
        assertThat(WalletTransactionType.ROULETTE_STAKE.isCredit()).isFalse();
        assertThat(WalletTransactionType.ROULETTE_PAYOUT.isCredit()).isTrue();
    }

    @Test
    void theApiPublishesEveryStoredTypeAndNothingElse() {
        // W3-08: responses show WalletEntryType only; every stored type has a public value of the same
        // name (and WalletService maps them with an exhaustive switch).
        assertThat(Arrays.stream(WalletEntryType.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(TransactionType.values()).map(Enum::name).toList());
    }
}
