package com.casino.common.system.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response of {@code GET /api/v1/system/status}.
 *
 * <p>Contract: {@code SystemStatus} schema in {@code openapi.yaml}.
 */
@Schema(name = "SystemStatus")
public record SystemStatusResponse(
        @Schema(requiredMode = REQUIRED) String application,
        @Schema(requiredMode = REQUIRED) String version,
        @Schema(requiredMode = REQUIRED) ComponentStatus status,
        @Schema(requiredMode = REQUIRED) Database database,
        @Schema(requiredMode = REQUIRED) Instant timestamp) {

    /** Contract: {@code DatabaseStatus} schema in {@code openapi.yaml}. */
    @Schema(name = "DatabaseStatus")
    public record Database(
            @Schema(requiredMode = REQUIRED) ComponentStatus status,
            @Schema(requiredMode = REQUIRED, nullable = true) String schemaVersion) {
    }
}
