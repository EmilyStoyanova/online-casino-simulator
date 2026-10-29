package com.casino.common.system.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlywayDatabaseProbeTest {

    private final Flyway flyway = mock(Flyway.class);
    private final MigrationInfoService infoService = mock(MigrationInfoService.class);
    private final FlywayDatabaseProbe probe = new FlywayDatabaseProbe(flyway);

    @BeforeEach
    void setUp() {
        when(flyway.info()).thenReturn(infoService);
    }

    @Test
    void reportsReachableWithLatestAppliedVersion() {
        MigrationInfo current = mock(MigrationInfo.class);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion("3"));
        when(infoService.current()).thenReturn(current);

        assertThat(probe.check()).isEqualTo(DatabaseHealth.up("3"));
    }

    @Test
    void reportsReachableWithoutVersionWhenNoMigrationWasApplied() {
        when(infoService.current()).thenReturn(null);

        assertThat(probe.check()).isEqualTo(DatabaseHealth.up(null));
    }

    @Test
    void reportsUnreachableInsteadOfThrowingWhenTheDatabaseFails() {
        when(infoService.current()).thenThrow(new FlywayException("Connection refused"));

        assertThat(probe.check()).isEqualTo(DatabaseHealth.down());
    }
}
