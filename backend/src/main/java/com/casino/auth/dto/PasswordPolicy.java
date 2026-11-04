package com.casino.auth.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Password policy of a registration (ADR-0006, week-02.md §7.3), reported as errors of the
 * {@code password} property:
 * <ul>
 *   <li>at least {@value PasswordPolicyValidator#MIN_CHARACTERS} characters (Unicode code points);</li>
 *   <li>at most {@value PasswordPolicyValidator#MAX_UTF8_BYTES} bytes in UTF-8, BCrypt's limit; longer
 *       passwords are rejected, never truncated;</li>
 *   <li>not a commonly used password ({@link CommonPasswords}), ignoring case and surrounding whitespace;</li>
 *   <li>not equal to the email or the display name, ignoring case and surrounding whitespace.</li>
 * </ul>
 */
@Documented
@Constraint(validatedBy = PasswordPolicyValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordPolicy {

    String message() default "does not meet the password policy";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
