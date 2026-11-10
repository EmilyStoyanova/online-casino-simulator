package com.casino.wallet.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/**
 * The wallet stayed locked by another operation for longer than {@code app.wallet.lock-timeout}:
 * {@code 503 SERVICE_UNAVAILABLE}. Nothing was changed; the caller may retry with the same key.
 */
public class WalletBusyException extends ApiException {

    public WalletBusyException() {
        super(ProblemCode.SERVICE_UNAVAILABLE);
    }
}
