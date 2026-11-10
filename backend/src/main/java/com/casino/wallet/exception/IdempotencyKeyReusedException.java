package com.casino.wallet.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/**
 * An operation key that already belongs to an operation with other values (type, amount, direction or
 * reference): {@code 409 IDEMPOTENCY_KEY_REUSED}. Nothing was changed; the detail names neither the
 * key nor the values.
 */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(ProblemCode.IDEMPOTENCY_KEY_REUSED);
    }
}
