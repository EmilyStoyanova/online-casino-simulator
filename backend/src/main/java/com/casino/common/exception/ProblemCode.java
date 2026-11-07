package com.casino.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes: the {@code code} property of {@code ProblemDetail} in
 * {@code openapi.yaml}. The default detail is a generic, user-facing message that never contains
 * internal information.
 *
 * <p>Every code has one fixed HTTP status, except the generic fallbacks {@link #CLIENT_ERROR} and
 * {@link #SERVER_ERROR}: they keep the status of an error that has no code of its own (e.g. a
 * {@code 410} from a {@code ResponseStatusException}), so the HTTP status is never changed.
 */
public enum ProblemCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request contains invalid values."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request could not be read."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Sign in to continue."),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "Your session has ended. Sign in again."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The email or password is incorrect."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to perform this action."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "The CSRF token is missing or invalid."),
    REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "New registrations are currently closed."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource does not exist."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported by this resource."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "None of the requested media types can be produced."),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state of the resource."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "An account with this email already exists."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was changed by another request. Reload it and try again."),
    CONTENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The request is too large."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported."),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable. Please try again later."),
    CLIENT_ERROR(HttpStatus.Series.CLIENT_ERROR, "The request was rejected."),
    SERVER_ERROR(HttpStatus.Series.SERVER_ERROR, "The request could not be completed. Please try again later.");

    private final HttpStatus status;
    private final HttpStatus.Series series;
    private final String defaultDetail;

    ProblemCode(HttpStatus status, String defaultDetail) {
        this.status = status;
        this.series = status.series();
        this.defaultDetail = defaultDetail;
    }

    ProblemCode(HttpStatus.Series series, String defaultDetail) {
        this.status = null;
        this.series = series;
        this.defaultDetail = defaultDetail;
    }

    /** Whether the code always comes with the same status ({@code false} for the generic fallbacks). */
    public boolean hasFixedStatus() {
        return status != null;
    }

    /**
     * The fixed status of this code.
     *
     * @throws IllegalStateException for {@link #CLIENT_ERROR} and {@link #SERVER_ERROR}
     */
    public HttpStatus status() {
        if (status == null) {
            throw new IllegalStateException(name() + " has no fixed status");
        }
        return status;
    }

    /** Whether a response with {@code statusCode} may carry this code. */
    public boolean allows(int statusCode) {
        return status != null ? status.value() == statusCode : series == HttpStatus.Series.resolve(statusCode);
    }

    public String defaultDetail() {
        return defaultDetail;
    }

    /**
     * The generic code for an error status that has no more specific cause, e.g. a Spring MVC
     * exception or an error forwarded to {@code /error}. The returned code always
     * {@linkplain #allows allows} the status, so the response keeps it.
     *
     * @throws IllegalArgumentException if {@code statusCode} is not a 4xx or 5xx status
     */
    public static ProblemCode forStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> ACCESS_DENIED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> CONTENT_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 500 -> INTERNAL_ERROR;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> {
                if (statusCode >= 400 && statusCode < 500) {
                    yield CLIENT_ERROR;
                }
                if (statusCode >= 500 && statusCode < 600) {
                    yield SERVER_ERROR;
                }
                throw new IllegalArgumentException("Not an error status: " + statusCode);
            }
        };
    }
}
