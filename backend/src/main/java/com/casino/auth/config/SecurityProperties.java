package com.casino.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings ({@code app.security.*}) that Spring Boot has no property for.
 *
 * @param bcryptStrength BCrypt cost factor (log2 rounds); at least 10, 12 by default
 */
@ConfigurationProperties(prefix = "app.security")
@Validated
public record SecurityProperties(@DefaultValue("12") @Min(10) @Max(31) int bcryptStrength) {
}
