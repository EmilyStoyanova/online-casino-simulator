package com.casino.wallet.exception;

import java.util.List;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.FieldViolation;

/**
 * The {@code cursor} of a history request is not a cursor issued by this API: {@code 400
 * VALIDATION_FAILED} with the field {@code cursor} (AC-3.4). The rejected value is never echoed.
 */
public class InvalidCursorException extends ApiException {

    public InvalidCursorException() {
        super(List.of(new FieldViolation("cursor", "is not a valid cursor")));
    }
}
