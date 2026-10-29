package com.casino.common.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.casino.common.system.dto.ComponentStatus;
import com.casino.common.system.dto.SystemStatusResponse;
import com.casino.common.system.service.SystemStatusService;
import com.casino.contract.OpenApiContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemStatusController.class)
class SystemStatusControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private static final SystemStatusResponse UP = new SystemStatusResponse(
            "online-casino-simulator", "1.2.3", ComponentStatus.UP,
            new SystemStatusResponse.Database(ComponentStatus.UP, "1"), NOW);

    private static final SystemStatusResponse DOWN = new SystemStatusResponse(
            "online-casino-simulator", "1.2.3", ComponentStatus.DOWN,
            new SystemStatusResponse.Database(ComponentStatus.DOWN, null), NOW);

    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemStatusService systemStatusService;

    @Test
    void everyResponseConformsToTheContract() throws Exception {
        when(systemStatusService.currentStatus())
                .thenReturn(UP)
                .thenReturn(DOWN)
                .thenThrow(new IllegalStateException("boom"));

        for (int expectedStatus : new int[] {200, 503, 500}) {
            MockHttpServletResponse response = mockMvc.perform(get("/api/v1/system/status")).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            assertThat(CONTRACT.validateResponse("/api/v1/system/status", "GET", response.getStatus(),
                    response.getContentType(), response.getContentAsString(StandardCharsets.UTF_8)))
                    .as("%d response vs openapi.yaml", expectedStatus)
                    .isEmpty();
        }
    }

    @Test
    void returnsOkWithStatusPayloadWhenPlatformIsUp() throws Exception {
        when(systemStatusService.currentStatus()).thenReturn(UP);

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.application").value("online-casino-simulator"))
                .andExpect(jsonPath("$.version").value("1.2.3"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.schemaVersion").value("1"))
                .andExpect(jsonPath("$.timestamp").value("2026-01-15T10:00:00Z"));
    }

    @Test
    void returnsServiceUnavailableWhenDatabaseIsDown() throws Exception {
        when(systemStatusService.currentStatus()).thenReturn(DOWN);

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database.status").value("DOWN"))
                // Required by the contract: present and explicitly null.
                .andExpect(jsonPath("$.database.schemaVersion").value(nullValue()));
    }

    @Test
    void allowsCorsPreflightFromConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/system/status")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void rejectsCorsPreflightFromUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/system/status")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void returnsProblemDetailsForUnsupportedMethod() throws Exception {
        mockMvc.perform(post("/api/v1/system/status"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.instance").value("/api/v1/system/status"));
    }

    @Test
    void returnsProblemDetailsForUnknownPath() throws Exception {
        mockMvc.perform(get("/api/v1/system/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() throws Exception {
        when(systemStatusService.currentStatus()).thenThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}
