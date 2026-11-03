package com.casino.auth.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings ({@code app.security.*}) that Spring Boot has no property for.
 *
 * @param bcryptStrength BCrypt cost factor (log2 rounds); at least 10, 12 by default
 * @param session        session lifetime beyond Spring Session's inactivity timeout
 */
@ConfigurationProperties(prefix = "app.security")
@Validated
public record SecurityProperties(@DefaultValue("12") @Min(10) @Max(31) int bcryptStrength,
                                 @DefaultValue @NotNull @Valid Session session) {

    /**
     * @param absoluteTimeout maximum lifetime of a signed-in session, however active it is (12 h by
     *                        default); the inactivity timeout is {@code spring.session.timeout}
     */
    public record Session(@DefaultValue("12h") @NotNull @DurationMin(minutes = 1) Duration absoluteTimeout) {
    }
}
