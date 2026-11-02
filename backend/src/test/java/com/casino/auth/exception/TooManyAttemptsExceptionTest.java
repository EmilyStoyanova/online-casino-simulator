package com.casino.auth.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.casino.common.exception.ProblemCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class TooManyAttemptsExceptionTest {

    @Test
    void isATooManyAttemptsProblemWithTheGenericDetail() {
        TooManyAttemptsException exception = new TooManyAttemptsException(Duration.ofMinutes(15));

        assertThat(exception.code()).isEqualTo(ProblemCode.TOO_MANY_ATTEMPTS);
        assertThat(exception.code().status().value()).isEqualTo(429);
        assertThat(exception.getMessage()).isEqualTo("Too many attempts. Try again later.");
        assertThat(exception.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900");
    }

    @Test
    void retryAfterIsRoundedUpToWholeSecondsAndAtLeastOne() {
        assertThat(seconds(Duration.ofSeconds(90))).isEqualTo(90);
        assertThat(seconds(Duration.ofMillis(89_001))).isEqualTo(90);
        assertThat(seconds(Duration.ofNanos(1_000))).isEqualTo(1);
        assertThat(seconds(Duration.ZERO)).isEqualTo(1);
        assertThat(seconds(Duration.ofSeconds(-5))).isEqualTo(1);
    }

    private static long seconds(Duration retryAfter) {
        return new TooManyAttemptsException(retryAfter).retryAfterSeconds();
    }
}
