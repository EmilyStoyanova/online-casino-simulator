package com.casino.common.exception;

import java.util.Objects;

import org.springframework.http.HttpHeaders;

/**
 * Base class of all module exceptions that map to an API error, e.g. an
 * {@code EmailAlreadyRegisteredException} in the {@code user} module. The
 * {@link GlobalExceptionHandler} turns it into a Problem Details response with the status of its
 * {@link ProblemCode}.
 *
 * <p>The message is the {@code detail} shown to the client, so it must be safe to expose: no ids of
 * other users, no internal values, no rejected input.
 */
public abstract class ApiException extends RuntimeException {

    private final ProblemCode code;

    protected ApiException(ProblemCode code) {
        this(code, code.defaultDetail());
    }

    protected ApiException(ProblemCode code, String detail) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        if (!code.hasFixedStatus()) {
            throw new IllegalArgumentException(code + " is a generic fallback; module exceptions need a specific code");
        }
    }

    public ProblemCode code() {
        return code;
    }

    /** Extra response headers of this error, e.g. {@code Retry-After} of a {@code 429}; none by default. */
    public HttpHeaders headers() {
        return HttpHeaders.EMPTY;
    }
}
