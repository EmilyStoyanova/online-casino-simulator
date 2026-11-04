package com.casino.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The first administrator ({@code app.bootstrap.admin.*}, AC-9.4), from
 * {@code APP_BOOTSTRAP_ADMIN_EMAIL} and {@code APP_BOOTSTRAP_ADMIN_PASSWORD}. Both empty (the
 * default) disables the bootstrap. Used by {@link AdminBootstrapRunner}.
 *
 * @param email    email of the administrator account
 * @param password its password; only hashed, never logged or stored
 */
@ConfigurationProperties(prefix = "app.bootstrap.admin")
public record BootstrapAdminProperties(String email, String password) {

    /** Whether at least one of the two values is set. */
    boolean configured() {
        return isSet(email) || isSet(password);
    }

    /** Whether both values are set. */
    boolean complete() {
        return isSet(email) && isSet(password);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Never shows the password (e.g. in logs or the actuator). */
    @Override
    public String toString() {
        return "BootstrapAdminProperties[email=" + (isSet(email) ? "<set>" : "<none>")
                + ", password=" + (isSet(password) ? "<redacted>" : "<none>") + "]";
    }
}
