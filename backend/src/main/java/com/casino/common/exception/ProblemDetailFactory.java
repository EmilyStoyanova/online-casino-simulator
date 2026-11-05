package com.casino.common.exception;

import java.net.URI;
import java.util.Collection;
import java.util.Comparator;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds every API error body (RFC 9457 Problem Details, {@code ProblemDetail} in
 * {@code openapi.yaml}). Used by the {@link GlobalExceptionHandler} and the {@code /error} fallback,
 * and later by the security entry point and access-denied handler.
 *
 * <p>The body consists only of the {@link ProblemCode}, its status and title, a safe detail message
 * and the request path. It never contains exception messages, stack traces or request values.
 */
public final class ProblemDetailFactory {

    /** Problem property with the {@link ProblemCode}. */
    public static final String CODE = "code";
    /** Problem property with the {@link FieldViolation}s of a {@code VALIDATION_FAILED} problem. */
    public static final String ERRORS = "errors";

    private static final Comparator<FieldViolation> BY_FIELD =
            Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message);

    private ProblemDetailFactory() {
    }

    /** A problem with the fixed status and the default detail of {@code code}. */
    public static ProblemDetail create(ProblemCode code, String path) {
        return create(code, code.defaultDetail(), path);
    }

    /**
     * A problem with the fixed status of {@code code}.
     *
     * @param detail user-facing explanation; must not contain internal information
     * @param path   request path (without query string) for {@code instance}; omitted when {@code null}
     * @throws IllegalStateException for a generic code without a fixed status
     */
    public static ProblemDetail create(ProblemCode code, String detail, String path) {
        return build(code, code.status().value(), detail, path);
    }

    /**
     * A problem that keeps {@code status} and carries its generic code ({@link ProblemCode#forStatus}),
     * e.g. for a Spring MVC exception or an error forwarded to {@code /error}.
     *
     * @throws IllegalArgumentException if {@code status} is not a 4xx or 5xx status
     */
    public static ProblemDetail forStatus(int status, String path) {
        ProblemCode code = ProblemCode.forStatus(status);
        return build(code, status, code.defaultDetail(), path);
    }

    /** A {@code VALIDATION_FAILED} problem listing the invalid properties, sorted by field. */
    public static ProblemDetail validationFailed(Collection<FieldViolation> violations, String path) {
        ProblemDetail problem = create(ProblemCode.VALIDATION_FAILED, path);
        problem.setProperty(ERRORS, violations.stream().sorted(BY_FIELD).toList());
        return problem;
    }

    private static ProblemDetail build(ProblemCode code, int status, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        HttpStatus known = HttpStatus.resolve(status);
        // Non-standard statuses (e.g. 499) get the name of their class as title.
        problem.setTitle(known != null ? known.getReasonPhrase() : status < 500 ? "Client Error" : "Server Error");
        problem.setInstance(toUri(path));
        problem.setProperty(CODE, code.name());
        return problem;
    }

    private static URI toUri(String path) {
        if (path == null) {
            return null;
        }
        try {
            return URI.create(path);
        } catch (IllegalArgumentException ex) {
            // A path that is not a valid URI reference is left out rather than failing the error response.
            return null;
        }
    }
}
