package com.casino.wallet.exception;

import java.util.UUID;

/**
 * A user that should have a wallet has none: a data-integrity error, never a reason to create one
 * on the fly. Not an {@code ApiException}: it is answered as {@code 500 INTERNAL_ERROR} with a
 * generic detail and logged with the user id only.
 */
public class WalletNotFoundException extends IllegalStateException {

    public WalletNotFoundException(UUID userId) {
        super("No wallet for user " + userId);
    }
}
