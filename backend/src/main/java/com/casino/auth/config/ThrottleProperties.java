package com.casino.auth.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Login and registration throttling ({@code app.security.throttle.*}, week-02.md §7.7). The limits are
 * in {@code application.yml}; the application does not start without them.
 *
 * @param loginAccountIp  failed logins per account and client IP (online guessing from one source)
 * @param loginIp         failed logins per client IP for any accounts (credential stuffing)
 * @param registerIp      registration attempts per client IP (mass registration, enumeration via {@code 409})
 * @param secret          Base64-encoded HMAC key (at least 32 bytes) of the stored throttle keys, from
 *                        {@code APP_SECURITY_THROTTLE_SECRET}; validated by {@code ThrottleKeys}
 * @param ephemeralSecret tests only: without a {@code secret}, use a random key per application start
 *                        instead of refusing to start
 */
@ConfigurationProperties(prefix = "app.security.throttle")
@Validated
public record ThrottleProperties(@NotNull @Valid Limit loginAccountIp,
                                 @NotNull @Valid Limit loginIp,
                                 @NotNull @Valid Limit registerIp,
                                 String secret,
                                 boolean ephemeralSecret) {

    /** Never shows the secret (e.g. in logs or the actuator). */
    @Override
    public String toString() {
        return "ThrottleProperties[loginAccountIp=" + loginAccountIp + ", loginIp=" + loginIp + ", registerIp=" + registerIp
                + ", secret=" + (secret == null || secret.isBlank() ? "<none>" : "<redacted>")
                + ", ephemeralSecret=" + ephemeralSecret + "]";
    }

    /**
     * At most {@code maxAttempts} counted attempts per fixed window of length {@code window}.
     *
     * @param maxAttempts attempts allowed per window
     * @param window      length of a window; a new one starts with the first attempt after it ends
     */
    public record Limit(@Min(1) @Max(10_000) int maxAttempts,
                        @NotNull @DurationMin(seconds = 1) @DurationMax(days = 1) Duration window) {
    }
}
