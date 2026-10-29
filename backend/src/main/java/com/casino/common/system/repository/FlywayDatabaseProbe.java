package com.casino.common.system.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Checks the database by reading Flyway's schema history. A successful read proves both
 * that the connection works and which migration the schema is on.
 */
@Repository
class FlywayDatabaseProbe implements DatabaseProbe {

    private static final Logger log = LoggerFactory.getLogger(FlywayDatabaseProbe.class);

    private final Flyway flyway;

    FlywayDatabaseProbe(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public DatabaseHealth check() {
        try {
            MigrationInfo current = flyway.info().current();
            return DatabaseHealth.up(current == null ? null : current.getVersion().getVersion());
        } catch (RuntimeException ex) {
            log.warn("Database status check failed: {}", ex.getMessage());
            return DatabaseHealth.down();
        }
    }
}
