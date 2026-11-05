package com.casino.auth.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/register}. Properties not listed here (e.g. {@code role}) are ignored.
 *
 * <p>Contract: {@code RegisterRequest} schema in {@code openapi.yaml}. The email and display name are
 * trimmed here, so validation checks the value that is stored; the password is never changed.
 * {@link PasswordPolicy} adds the rules that compare the password with the other properties.
 */
@Schema(name = "RegisterRequest")
@PasswordPolicy
public record RegisterRequest(
        @Schema(requiredMode = REQUIRED, format = "email")
        @NotBlank(message = "must not be blank")
        @Size(max = 254, message = "must be at most 254 characters long")
        // ASCII only: Java and PostgreSQL lower-case some other characters differently.
        @Email(regexp = "\\p{ASCII}*", message = "must be a valid email address")
        String email,

        @Schema(requiredMode = REQUIRED)
        @NotNull(message = "must not be null")
        String password,

        @Schema(requiredMode = REQUIRED)
        @NotBlank(message = "must not be blank")
        @Size(min = 3, max = 30, message = "must be 3 to 30 characters long")
        @Pattern(regexp = "\\P{Cc}*", message = "must not contain control characters")
        String displayName,

        @Schema(requiredMode = REQUIRED)
        @NotNull(message = "must be true")
        @AssertTrue(message = "must be true")
        Boolean adultConfirmed) {

    public RegisterRequest {
        email = email == null ? null : email.strip();
        displayName = displayName == null ? null : displayName.strip();
    }

    /** Never shows the password. */
    @Override
    public String toString() {
        return "RegisterRequest[displayName=" + displayName + ", adultConfirmed=" + adultConfirmed + "]";
    }
}
