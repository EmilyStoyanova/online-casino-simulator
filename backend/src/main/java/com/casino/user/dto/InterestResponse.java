package com.casino.user.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Educational interest from the catalogue.
 *
 * <p>Contract: {@code Interest} schema in {@code openapi.yaml}.
 */
@Schema(name = "Interest")
public record InterestResponse(
        @Schema(requiredMode = REQUIRED, pattern = "^[A-Z][A-Z0-9_]*$", maxLength = 40) String code,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) String description) {
}
