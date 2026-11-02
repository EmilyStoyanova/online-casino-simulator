package com.casino.auth.exception;

import java.time.Duration;
import java.util.Objects;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;
import org.springframework.http.HttpHeaders;

/**
 * A throttled login or registration attempt: {@code 429 TOO_MANY_ATTEMPTS} with {@code Retry-After}
 * (whole seconds until the throttling window ends, at least 1). The response is the same for
 * existing and unknown accounts (AC-6.4).
 */
public class TooManyAttemptsException extends ApiException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super(ProblemCode.TOO_MANY_ATTEMPTS);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** {@code Retry-After} in seconds, rounded up, so a client that waits exactly this long is no longer blocked. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds()));
        return headers;
    }
}
