package com.casino.common.system.service;

import java.time.Clock;

import com.casino.common.system.dto.ComponentStatus;
import com.casino.common.system.dto.SystemStatusResponse;
import com.casino.common.system.repository.DatabaseHealth;
import com.casino.common.system.repository.DatabaseProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds the platform status reported to clients and monitoring. */
@Service
public class SystemStatusService {

    private final DatabaseProbe databaseProbe;
    private final Clock clock;
    private final String applicationName;
    private final String version;

    public SystemStatusService(
            DatabaseProbe databaseProbe,
            Clock clock,
            @Value("${spring.application.name}") String applicationName,
            @Value("${app.version}") String version) {
        this.databaseProbe = databaseProbe;
        this.clock = clock;
        this.applicationName = applicationName;
        this.version = version;
    }

    public SystemStatusResponse currentStatus() {
        DatabaseHealth health = databaseProbe.check();
        ComponentStatus databaseStatus = health.reachable() ? ComponentStatus.UP : ComponentStatus.DOWN;
        // The platform cannot serve any meaningful request without its database.
        return new SystemStatusResponse(
                applicationName,
                version,
                databaseStatus,
                new SystemStatusResponse.Database(databaseStatus, health.schemaVersion()),
                clock.instant());
    }
}
