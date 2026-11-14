package com.casino.wallet.service;

import com.casino.common.exception.ApiException;
import com.casino.user.dto.UserRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the wallet of every newly registered player ({@code docs/plans/week-03.md} §7.3, AC-1.1).
 *
 * <p>A plain synchronous {@link EventListener}: it runs in the publishing thread, inside the
 * registration transaction of {@code UserAccountService.createAccount}, so the account, the wallet and
 * the starting entry commit together, and a failure here rolls the whole registration back. Not
 * {@code @TransactionalEventListener} (after commit an account could stay without a wallet) and not
 * {@code @Async}. {@link Propagation#MANDATORY} turns a publication outside a transaction into an error
 * instead of a separate, non-atomic write.
 *
 * <p>The dependency points from {@code wallet} to {@code user.dto} only (ArchUnit R14); the
 * {@code user} module does not know the wallet. The administrator bootstrap publishes no event, so
 * administrators get no wallet.
 */
@Component
public class WalletOpener {

    private final WalletService walletService;

    public WalletOpener(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * @throws IllegalStateException if the wallet cannot be opened; the registration fails with
     *                               {@code 500 INTERNAL_ERROR} (AC-1.2), never with a wallet error code
     *                               that would describe an operation the client did not request
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onUserRegistered(UserRegistered event) {
        try {
            walletService.openWallet(event.userId());
        } catch (ApiException ex) {
            throw new IllegalStateException("The wallet of a new player could not be opened", ex);
        }
    }
}
