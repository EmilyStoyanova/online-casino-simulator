package com.casino.wallet.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;
import org.junit.jupiter.api.Test;

/**
 * The wallet errors carry their stable code and its neutral default detail: no amount, balance, key or
 * id. {@code GlobalExceptionHandler} turns every {@link ApiException} into Problem Details with that
 * status, code and detail and {@code Cache-Control: no-store} ({@code GlobalExceptionHandlerTest}).
 */
class WalletExceptionsTest {

    @Test
    void walletConflictsAre409WithTheirOwnCodeAndANeutralDetail() {
        List<ApiException> errors = List.of(new InsufficientFundsException(), new IdempotencyKeyReusedException(),
                new BalanceLimitExceededException());

        assertThat(errors).extracting(ApiException::code).containsExactly(ProblemCode.INSUFFICIENT_FUNDS,
                ProblemCode.IDEMPOTENCY_KEY_REUSED, ProblemCode.BALANCE_LIMIT_EXCEEDED);
        assertThat(errors).allSatisfy(error -> {
            assertThat(error.code().status().value()).isEqualTo(409);
            assertThat(error.getMessage()).isEqualTo(error.code().defaultDetail()).doesNotContainPattern("\\d");
            assertThat(error.violations()).isEmpty();
        });
        assertThat(errors).extracting(Throwable::getMessage).containsExactly(
                "The virtual balance is too low for this action.",
                "This request was already processed with different values.",
                "The virtual balance would exceed its maximum.");
    }

    @Test
    void aBusyWalletIsTemporarilyUnavailable() {
        WalletBusyException busy = new WalletBusyException();

        assertThat(busy.code()).isEqualTo(ProblemCode.SERVICE_UNAVAILABLE);
        assertThat(busy.code().status().value()).isEqualTo(503);
    }

    @Test
    void aMissingWalletIsAnInternalErrorThatOnlyNamesTheUser() {
        UUID user = UUID.randomUUID();

        // Not an ApiException: answered as a generic 500, logged with the user id only.
        assertThat(new WalletNotFoundException(user)).isNotInstanceOf(ApiException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No wallet for user " + user);
    }
}
