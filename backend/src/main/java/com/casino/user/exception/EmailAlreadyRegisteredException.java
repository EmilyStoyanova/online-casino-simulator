package com.casino.user.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/** Registration with an email that already has an account: {@code 409 EMAIL_ALREADY_REGISTERED}. */
public class EmailAlreadyRegisteredException extends ApiException {

    public EmailAlreadyRegisteredException() {
        super(ProblemCode.EMAIL_ALREADY_REGISTERED);
    }
}
