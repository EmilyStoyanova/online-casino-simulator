package com.casino.auth.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/**
 * A registration while registration is closed by configuration ({@code APP_REGISTRATION_ENABLED=false}):
 * {@code 403 REGISTRATION_DISABLED}.
 */
public class RegistrationDisabledException extends ApiException {

    public RegistrationDisabledException() {
        super(ProblemCode.REGISTRATION_DISABLED);
    }
}
