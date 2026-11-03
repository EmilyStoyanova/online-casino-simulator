package com.casino.auth.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}. Contract: {@code LoginRequest} schema in {@code openapi.yaml}.
 *
 * <p>Only presence and the email length are validated; whether the credentials are right is answered
 * with the same {@code 401} for every failure.
 */
@Schema(name = "LoginRequest")
public record LoginRequest(
        @Schema(requiredMode = REQUIRED)
        @NotBlank(message = "must not be blank")
        @Size(max = 254, message = "must be at most 254 characters long")
        String email,

        @Schema(requiredMode = REQUIRED)
        @NotEmpty(message = "must not be empty")
        String password) {

    /** Never shows the password. */
    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
