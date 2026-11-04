package com.casino.user.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/**
 * The account of a valid session no longer exists: {@code 401 AUTHENTICATION_REQUIRED}, so the
 * client treats the user as signed out and can sign in again. The API cannot delete accounts; this
 * happens only after a manual deletion in the database without deleting the user's sessions
 * ({@code docs/database.md}).
 */
public class AccountNotFoundException extends ApiException {

    public AccountNotFoundException() {
        super(ProblemCode.AUTHENTICATION_REQUIRED);
    }
}
