package com.casino.auth.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/**
 * Any failed login, whatever the reason (unknown email, wrong password, disabled account):
 * {@code 401 INVALID_CREDENTIALS} with the same body, so the response does not reveal whether the
 * account exists (AC-2.2). Deliberately not a Spring Security {@code AuthenticationException}, which
 * {@code GlobalExceptionHandler} would hand to the entry point.
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(ProblemCode.INVALID_CREDENTIALS);
    }
}
