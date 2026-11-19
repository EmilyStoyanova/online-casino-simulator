package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.casino.game.service.RouletteWheel;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the full application against a real PostgreSQL instance (Testcontainers) and
 * verifies the Week 1 foundation end to end: connectivity, migrations and public endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CasinoSimulatorApplicationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private List<RouletteWheel> wheels;

    @Test
    void appliesFlywayMigrationsOnStartup() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");

        Integer functionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_proc WHERE proname = 'set_updated_at'", Integer.class);
        assertThat(functionCount).isEqualTo(1);
        // V2: Spring Session JDBC schema (unquoted names are lower-cased by PostgreSQL).
        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'spring_session%'", String.class))
                .containsExactlyInAnyOrder("spring_session", "spring_session_attributes");
    }

    @Test
    void theOnlyRouletteWheelIsTheSecureRandomWheel() {
        // W4-04: no other wheel (fixed, seeded, test) exists in the application context.
        assertThat(wheels).singleElement()
                .satisfies(wheel -> assertThat(wheel.getClass().getName()).isEqualTo("com.casino.game.service.SecureRandomRouletteWheel"))
                .satisfies(wheel -> assertThat(wheel.spin()).isBetween(0, 36));
    }

    @Test
    void systemStatusReportsDatabaseUpWithCurrentSchemaVersion() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.schemaVersion").value("9"))
                .andExpect(jsonPath("$.version").isNotEmpty());
    }

    @Test
    void actuatorHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void publishesOpenApiSpecification() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Online Casino Simulator API"))
                .andExpect(jsonPath("$.paths['/api/v1/system/status']").exists())
                // The /error fallback is infrastructure, not part of the API.
                .andExpect(jsonPath("$.paths['/error']").doesNotExist());
    }
}
