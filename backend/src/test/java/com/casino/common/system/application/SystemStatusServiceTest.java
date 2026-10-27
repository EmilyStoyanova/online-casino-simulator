package com.casino.common.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private DatabaseProbe databaseProbe;

    private SystemStatusService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new SystemStatusService(databaseProbe, fixedClock, "online-casino-simulator", "1.2.3");
    }

    @Test
    void reportsUpWhenDatabaseIsReachable() {
        when(databaseProbe.check()).thenReturn(DatabaseHealth.up("1"));

        SystemStatus status = service.currentStatus();

        assertThat(status.isUp()).isTrue();
        assertThat(status.status()).isEqualTo(ComponentStatus.UP);
        assertThat(status.database()).isEqualTo(new DatabaseHealth(ComponentStatus.UP, "1"));
        assertThat(status.application()).isEqualTo("online-casino-simulator");
        assertThat(status.version()).isEqualTo("1.2.3");
        assertThat(status.timestamp()).isEqualTo(NOW);
    }

    @Test
    void reportsDownWhenDatabaseIsUnreachable() {
        when(databaseProbe.check()).thenReturn(DatabaseHealth.down());

        SystemStatus status = service.currentStatus();

        assertThat(status.isUp()).isFalse();
        assertThat(status.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(status.database().schemaVersion()).isNull();
    }
}
