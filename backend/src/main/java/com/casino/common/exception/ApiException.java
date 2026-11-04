package com.casino.common.exception;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpHeaders;

/**
 * Base class of all module exceptions that map to an API error, e.g. an
 * {@code EmailAlreadyRegisteredException} in the {@code user} module. The
 * {@link GlobalExceptionHandler} turns it into a Problem Details response with the status of its
 * {@link ProblemCode}.
 *
 * <p>The message is the {@code detail} shown to the client, so it must be safe to expose: no ids of
 * other users, no internal values, no rejected input. The same applies to the messages of
 * {@link #violations()}.
 */
public abstract class ApiException extends RuntimeException {

    private final ProblemCode code;
    private final List<FieldViolation> violations;

    protected ApiException(ProblemCode code) {
        this(code, code.defaultDetail());
    }

    protected ApiException(ProblemCode code, String detail) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        this.violations = List.of();
        if (!code.hasFixedStatus()) {
            throw new IllegalArgumentException(code + " is a generic fallback; module exceptions need a specific code");
        }
    }

    /**
     * A {@code VALIDATION_FAILED} error found by a service rather than by Bean Validation, e.g. an
     * unknown reference, answered like a Bean Validation failure.
     *
     * @param violations the invalid properties; at least one
     */
    protected ApiException(List<FieldViolation> violations) {
        super(ProblemCode.VALIDATION_FAILED.defaultDetail());
        this.code = ProblemCode.VALIDATION_FAILED;
        this.violations = List.copyOf(violations);
        if (this.violations.isEmpty()) {
            throw new IllegalArgumentException("A validation failure needs at least one violation");
        }
    }

    public ProblemCode code() {
        return code;
    }

    /** The invalid properties of a {@code VALIDATION_FAILED} error; empty for every other error. */
    public List<FieldViolation> violations() {
        return violations;
    }

    /** Extra response headers of this error, e.g. {@code Retry-After} of a {@code 429}; none by default. */
    public HttpHeaders headers() {
        return HttpHeaders.EMPTY;
    }
}
