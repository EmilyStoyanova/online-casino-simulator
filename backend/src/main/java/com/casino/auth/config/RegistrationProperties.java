package com.casino.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether new player accounts can be registered ({@code app.registration.enabled}, from
 * {@code APP_REGISTRATION_ENABLED}). Server configuration only: no request can change it. Closed
 * registration rejects {@code POST /api/v1/auth/register} with {@code 403 REGISTRATION_DISABLED};
 * login, logout and existing sessions are unaffected.
 *
 * @param enabled {@code true} by default (local development); the profile {@code render} defaults to
 *                {@code false}, so a public deployment opens registration only on purpose
 */
@ConfigurationProperties(prefix = "app.registration")
public record RegistrationProperties(@DefaultValue("true") boolean enabled) {
}
