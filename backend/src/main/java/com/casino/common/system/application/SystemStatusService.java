package com.casino.common.system.application;

import java.time.Clock;

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

    public SystemStatus currentStatus() {
        DatabaseHealth database = databaseProbe.check();
        // The platform cannot serve any meaningful request without its database.
        ComponentStatus overall = database.status() == ComponentStatus.UP ? ComponentStatus.UP : ComponentStatus.DOWN;
        return new SystemStatus(applicationName, version, overall, database, clock.instant());
    }
}
