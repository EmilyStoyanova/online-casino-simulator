package com.casino.user.exception;

import java.util.List;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.FieldViolation;

/**
 * A profile update selects interests that do not exist or are no longer offered:
 * {@code 400 VALIDATION_FAILED} with one error per position, e.g. {@code interests[2]}. The codes
 * themselves are not echoed.
 */
public class UnknownInterestException extends ApiException {

    static final String MESSAGE = "must be the code of an active interest";

    /** @param positions indexes in the request's {@code interests}, at least one */
    public UnknownInterestException(List<Integer> positions) {
        super(positions.stream().map(index -> new FieldViolation("interests[" + index + "]", MESSAGE)).toList());
    }
}
