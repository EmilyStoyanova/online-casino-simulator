package com.casino.common.system.application;

import java.time.Instant;

/** Snapshot of the platform status at a point in time. */
public record SystemStatus(
        String application,
        String version,
        ComponentStatus status,
        DatabaseHealth database,
        Instant timestamp) {

    public boolean isUp() {
        return status == ComponentStatus.UP;
    }
}
