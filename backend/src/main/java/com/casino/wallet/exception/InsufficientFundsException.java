package com.casino.wallet.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/** A debit larger than the balance: {@code 409 INSUFFICIENT_FUNDS}. Nothing was changed. */
public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException() {
        super(ProblemCode.INSUFFICIENT_FUNDS);
    }
}
