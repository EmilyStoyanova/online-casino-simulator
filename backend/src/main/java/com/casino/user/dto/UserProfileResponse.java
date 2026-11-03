package com.casino.user.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Profile of the signed-in user; never contains the password or its hash.
 *
 * <p>Contract: {@code UserProfile} schema in {@code openapi.yaml}.
 *
 * @param interests codes of the selected interests; empty until interests exist (W2-10)
 */
@Schema(name = "UserProfile")
public record UserProfileResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED, format = "email") String email,
        @Schema(requiredMode = REQUIRED) String displayName,
        @Schema(requiredMode = REQUIRED) UserRole role,
        @Schema(requiredMode = REQUIRED) List<String> interests,
        @Schema(requiredMode = REQUIRED) Instant adultConfirmedAt,
        @Schema(requiredMode = REQUIRED) Instant createdAt) {

    public UserProfileResponse {
        interests = List.copyOf(interests);
    }
}
