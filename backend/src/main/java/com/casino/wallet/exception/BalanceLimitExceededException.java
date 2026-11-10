package com.casino.wallet.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/** A credit that would raise the balance above 2^53 - 1: {@code 409 BALANCE_LIMIT_EXCEEDED}. Nothing was changed. */
public class BalanceLimitExceededException extends ApiException {

    public BalanceLimitExceededException() {
        super(ProblemCode.BALANCE_LIMIT_EXCEEDED);
    }
}
