package com.casino.auth.dto;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link PasswordPolicy}; each broken rule is one error of the {@code password} property. */
public class PasswordPolicyValidator implements ConstraintValidator<PasswordPolicy, RegisterRequest> {

    static final int MIN_CHARACTERS = 12;
    static final int MAX_UTF8_BYTES = 72;
    static final String PASSWORD = "password";

    @Override
    public boolean isValid(RegisterRequest request, ConstraintValidatorContext context) {
        String password = request == null ? null : request.password();
        if (password == null) {
            // Reported by @NotNull.
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        if (password.codePointCount(0, password.length()) < MIN_CHARACTERS) {
            valid = reject(context, "must be at least " + MIN_CHARACTERS + " characters long");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            valid = reject(context, "must be at most " + MAX_UTF8_BYTES + " bytes long in UTF-8");
        }
        if (matches(password, request.email())) {
            valid = reject(context, "must not equal the email");
        }
        if (matches(password, request.displayName())) {
            valid = reject(context, "must not equal the display name");
        }
        return valid;
    }

    private static boolean matches(String password, String other) {
        return other != null && password.strip().equalsIgnoreCase(other.strip());
    }

    private static boolean reject(ConstraintValidatorContext context, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(PASSWORD).addConstraintViolation();
        return false;
    }
}
