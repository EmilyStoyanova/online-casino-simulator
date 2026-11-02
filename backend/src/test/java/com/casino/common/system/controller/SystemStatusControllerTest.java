package com.casino.common.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.casino.auth.config.SecurityConfig;
import com.casino.common.config.WebConfig;
import com.casino.common.system.dto.ComponentStatus;
import com.casino.common.system.dto.SystemStatusResponse;
import com.casino.common.system.service.SystemStatusService;
import com.casino.contract.OpenApiContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Runs through the real security filter chain (public endpoint, CORS, CSRF). */
@WebMvcTest(SystemStatusController.class)
@Import({SecurityConfig.class, WebConfig.class})
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
    void rejectsCorsRequestsFromUnknownOrigin() throws Exception {
        // Spring's CORS processor answers before the application: a fixed plain-text 403 without
        // CORS headers, so the browser exposes nothing to the foreign page (documented exception).
        for (MockHttpServletRequestBuilder request : List.of(
                options("/api/v1/system/status").header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"),
                get("/api/v1/system/status"))) {
            mockMvc.perform(request.header(HttpHeaders.ORIGIN, "https://evil.example.com"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                    .andExpect(content().string("Invalid CORS request"));
        }
    }

    @Test
    void returnsProblemDetailsForUnsupportedMethods() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(post("/api/v1/system/status"), put("/api/v1/system/status"),
                patch("/api/v1/system/status"), delete("/api/v1/system/status"))) {
            mockMvc.perform(request.with(csrf()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.status").value(405))
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/system/status"));
        }
    }

    @Test
    void headIsSupportedLikeGet() throws Exception {
        when(systemStatusService.currentStatus()).thenReturn(UP);

        // Permitted like GET (the servlet container drops the body of a HEAD response; MockMvc does not).
        mockMvc.perform(head("/api/v1/system/status")).andExpect(status().isOk());
    }

    @Test
    void unsupportedMethodWithoutCsrfTokenIsRejectedFirst() throws Exception {
        mockMvc.perform(post("/api/v1/system/status"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void returnsProblemDetailsForUnknownPath() throws Exception {
        // Deny by default: without a session an unknown API path is 401, with one it is 404.
        mockMvc.perform(get("/api/v1/system/unknown"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/system/unknown").with(user("player")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() throws Exception {
        when(systemStatusService.currentStatus()).thenThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}
