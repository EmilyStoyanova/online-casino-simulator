package com.casino.common.system.api;

import java.time.Instant;

import com.casino.common.system.application.ComponentStatus;
import com.casino.common.system.application.SystemStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Public API representation of the platform status. */
@Schema(name = "SystemStatus", description = "Platform status and database connectivity")
public record SystemStatusResponse(
        @Schema(example = "online-casino-simulator") String application,
        @Schema(example = "0.1.0-SNAPSHOT") String version,
        ComponentStatus status,
        Database database,
        Instant timestamp) {

    @Schema(name = "DatabaseStatus")
    public record Database(
            ComponentStatus status,
            @Schema(description = "Latest applied Flyway migration version", example = "1", nullable = true)
            String schemaVersion) {
    }

    static SystemStatusResponse from(SystemStatus status) {
        return new SystemStatusResponse(
                status.application(),
                status.version(),
                status.status(),
                new Database(status.database().status(), status.database().schemaVersion()),
                status.timestamp());
    }
}
