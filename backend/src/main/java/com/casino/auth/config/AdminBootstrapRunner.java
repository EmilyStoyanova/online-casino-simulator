package com.casino.auth.config;

import java.util.Set;
import java.util.stream.Collectors;

import com.casino.auth.dto.RegisterRequest;
import com.casino.user.dto.NewAccount;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRole;
import com.casino.user.exception.EmailAlreadyRegisteredException;
import com.casino.user.service.UserAccountService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator at start-up (AC-9.4, S16): the API never assigns {@code ADMIN}.
 *
 * <ul>
 *   <li>Only when {@code APP_BOOTSTRAP_ADMIN_EMAIL} and {@code APP_BOOTSTRAP_ADMIN_PASSWORD} are both
 *       set; with only one of them the application does not start.</li>
 *   <li>The values must pass the registration rules (email format, password policy); otherwise the
 *       application does not start. The error names the rule, never the value.</li>
 *   <li>Idempotent: if an account with the email exists, nothing is changed, whatever its role. An
 *       existing account is never promoted. Concurrent starts of several instances are safe (the
 *       unique email decides).</li>
 *   <li>The password is only hashed (BCrypt); it is never logged or stored. Remove both values from
 *       the environment after the first start.</li>
 * </ul>
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    /** Display name of the bootstrap administrator; it can be changed in the profile later. */
    static final String DISPLAY_NAME = "Administrator";

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final BootstrapAdminProperties properties;
    private final UserAccountService accounts;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    public AdminBootstrapRunner(BootstrapAdminProperties properties, UserAccountService accounts,
                                PasswordEncoder passwordEncoder, Validator validator) {
        this.properties = properties;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.configured()) {
            log.debug("Administrator bootstrap disabled (APP_BOOTSTRAP_ADMIN_EMAIL/PASSWORD not set)");
            return;
        }
        if (!properties.complete()) {
            throw new IllegalStateException(
                    "Set both APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD to create the first administrator, or neither");
        }
        RegisterRequest admin = new RegisterRequest(properties.email(), properties.password(), DISPLAY_NAME, true);
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(admin);
        if (!violations.isEmpty()) {
            // Property names and rule messages only; the messages never contain the rejected value.
            throw new IllegalStateException("The bootstrap administrator is invalid: " + violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
        if (accounts.findCredentials(admin.email()).isPresent()) {
            reportExisting(admin.email());
            return;
        }
        try {
            UserProfileResponse created = accounts.createAdministrator(
                    new NewAccount(admin.email(), passwordEncoder.encode(admin.password()), DISPLAY_NAME, true));
            log.info("Created the bootstrap administrator account {}", created.id());
        } catch (EmailAlreadyRegisteredException ex) {
            // Another instance created it at the same moment.
            reportExisting(admin.email());
        }
    }

    private void reportExisting(String email) {
        boolean administrator = accounts.findCredentials(email)
                .map(credentials -> credentials.role() == UserRole.ADMIN)
                .orElse(false);
        if (administrator) {
            log.info("The bootstrap administrator already exists; nothing was changed");
        } else {
            log.warn("An account with the bootstrap administrator's email exists and is not an administrator. "
                    + "It was not changed: existing accounts are never promoted.");
        }
    }
}
