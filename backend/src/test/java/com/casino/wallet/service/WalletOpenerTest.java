package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;

import com.casino.user.dto.UserRegistered;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.exception.WalletBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** {@link WalletOpener}: one wallet per registration, synchronously and inside the registration transaction. */
class WalletOpenerTest {

    private static final UUID USER = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");

    private final WalletService walletService = mock(WalletService.class);
    private final WalletOpener opener = new WalletOpener(walletService);

    @Test
    void aRegisteredPlayerGetsTheirWalletOpened() {
        when(walletService.openWallet(USER)).thenReturn(new WalletOperationResult(1, 1000, false));

        opener.onUserRegistered(new UserRegistered(USER));

        verify(walletService).openWallet(USER);
    }

    @Test
    void aWalletErrorFailsTheRegistrationAsAnInternalError() {
        // Never a wallet code (409/503) for a registration request: AC-1.2 answers 500 INTERNAL_ERROR.
        WalletBusyException cause = new WalletBusyException();
        when(walletService.openWallet(USER)).thenThrow(cause);

        assertThatThrownBy(() -> opener.onUserRegistered(new UserRegistered(USER)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasCause(cause)
                .hasMessageNotContaining(USER.toString());
    }

    @Test
    void theListenerIsSynchronousAndRequiresTheRegistrationTransaction() throws NoSuchMethodException {
        Method listener = WalletOpener.class.getMethod("onUserRegistered", UserRegistered.class);

        assertThat(listener.getAnnotation(EventListener.class)).isNotNull();
        assertThat(listener.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(listener.getAnnotation(TransactionalEventListener.class)).isNull();
        assertThat(listener.getAnnotation(Async.class)).isNull();
        assertThat(WalletOpener.class.getAnnotation(Async.class)).isNull();
    }
}
