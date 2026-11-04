package com.casino.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.casino.auth.config.SecurityConfig;
import com.casino.common.config.WebConfig;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import com.casino.user.dto.UpdateProfileRequest;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRole;
import com.casino.user.exception.AccountNotFoundException;
import com.casino.user.exception.ConcurrentModificationException;
import com.casino.user.exception.UnknownInterestException;
import com.casino.user.service.UserProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
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

/**
 * {@code GET/PATCH /api/v1/users/me} through the real security filter chain with a mocked service:
 * authentication, CSRF, request binding and validation, error mapping and the contract.
 */
@WebMvcTest(UserProfileController.class)
@Import({SecurityConfig.class, WebConfig.class})
class UserProfileControllerTest {

    private static final String PATH = "/api/v1/users/me";
    private static final UUID ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");
    private static final Instant CREATED = Instant.parse("2026-11-02T09:15:00Z");
    private static final UserProfileResponse PROFILE = new UserProfileResponse(ID, "alice@example.com", "Alice",
            UserRole.PLAYER, List.of("PROBABILITY", "MATHEMATICS"), CREATED, CREATED);
    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @Test
    void returnsTheProfileOfTheSessionsUser() throws Exception {
        when(userProfileService.getProfile(ID)).thenReturn(PROFILE);

        MockHttpServletResponse response = expect("GET", get(PATH).with(user(ID.toString())), 200);

        assertThat(json(response).path("interests").toString()).isEqualTo("[\"PROBABILITY\",\"MATHEMATICS\"]");
        assertThat(body(response)).doesNotContain("password", "Hash", "version");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        expectProblem("GET", get(PATH), ProblemCode.AUTHENTICATION_REQUIRED);
        expectProblem("PATCH", patchJson("{\"displayName\":\"Mallory\"}").with(csrf()), ProblemCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(userProfileService);
    }

    @Test
    void updatesRequireACsrfToken() throws Exception {
        expectProblem("PATCH", patchJson("{\"displayName\":\"Mallory\"}").with(user(ID.toString())),
                ProblemCode.CSRF_TOKEN_INVALID);
        verifyNoInteractions(userProfileService);
    }

    @Test
    void updatesTheSessionsUserWithTheEditablePropertiesOnly() throws Exception {
        when(userProfileService.updateProfile(eq(ID), any())).thenReturn(PROFILE);

        // AC-7.3 / AC-9.3: other properties, including another user's id, are ignored.
        expect("PATCH", signedIn(patchJson("""
                {"displayName":"  Alice B.  ","interests":["STATISTICS"],"id":"%s","email":"mallory@example.com",
                 "role":"ADMIN","status":"DISABLED","adultConfirmedAt":"2000-01-01T00:00:00Z"}"""
                .formatted(UUID.randomUUID()))), 200);

        // The display name is trimmed before it is validated and passed on.
        verify(userProfileService).updateProfile(ID, new UpdateProfileRequest("Alice B.", List.of("STATISTICS")));
    }

    @Test
    void omittedPropertiesArePassedOnAsUnchanged() throws Exception {
        when(userProfileService.updateProfile(eq(ID), any())).thenReturn(PROFILE);

        expect("PATCH", signedIn(patchJson("{}")), 200);
        // null never clears a property: it is the same as omitting it.
        expect("PATCH", signedIn(patchJson("{\"displayName\":null,\"interests\":null}")), 200);
        expect("PATCH", signedIn(patchJson("{\"interests\":[]}")), 200);

        verify(userProfileService, times(2)).updateProfile(ID, new UpdateProfileRequest(null, null));
        verify(userProfileService).updateProfile(ID, new UpdateProfileRequest(null, List.of()));
    }

    @Test
    void invalidValuesAreReportedPerProperty() throws Exception {
        Map<String, Map<String, String>> cases = Map.of(
                "{\"displayName\":\"  Al \"}", Map.of("displayName", "must be 3 to 30 characters long"),
                "{\"displayName\":\"" + "x".repeat(31) + "\"}", Map.of("displayName", "must be 3 to 30 characters long"),
                "{\"displayName\":\"Ali\\u0007ce\"}", Map.of("displayName", "must not contain control characters"),
                "{\"interests\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\",\"H\",\"I\"]}",
                Map.of("interests", "must contain at most 8 interests"),
                "{\"interests\":[\"PROBABILITY\",\"PROBABILITY\"]}", Map.of("interests", "must not contain the same interest twice"),
                "{\"interests\":[\"PROBABILITY\",\"probability\",null]}",
                Map.of("interests[1]", "must be an interest code", "interests[2]", "must not be null"),
                "{\"interests\":[\"" + "A".repeat(41) + "\"]}", Map.of("interests[0]", "must be at most 40 characters long"));

        for (Map.Entry<String, Map<String, String>> entry : cases.entrySet()) {
            JsonNode problem = json(expectProblem("PATCH", signedIn(patchJson(entry.getKey())), ProblemCode.VALIDATION_FAILED));
            Map<String, String> errors = new HashMap<>();
            problem.path("errors").forEach(error -> errors.put(error.path("field").asText(), error.path("message").asText()));
            assertThat(errors).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        verifyNoInteractions(userProfileService);
    }

    @Test
    void unreadableBodiesAreMalformed() throws Exception {
        for (String body : List.of("{\"interests\":\"PROBABILITY\"}", "{\"interests\":{}}", "{\"displayName\":",
                "[]", "")) {
            expectProblem("PATCH", signedIn(patchJson(body)), ProblemCode.MALFORMED_REQUEST);
        }
        verifyNoInteractions(userProfileService);
    }

    @Test
    void serviceErrorsKeepTheirCode() throws Exception {
        when(userProfileService.updateProfile(eq(ID), any()))
                .thenThrow(new UnknownInterestException(List.of(1)))
                .thenThrow(new ConcurrentModificationException())
                .thenThrow(new AccountNotFoundException());
        String body = "{\"interests\":[\"PROBABILITY\",\"ASTROLOGY\"]}";

        JsonNode unknown = json(expectProblem("PATCH", signedIn(patchJson(body)), ProblemCode.VALIDATION_FAILED));
        assertThat(unknown.path("errors").toString())
                .isEqualTo("[{\"field\":\"interests[1]\",\"message\":\"must be the code of an active interest\"}]");
        JsonNode conflict = json(expectProblem("PATCH", signedIn(patchJson(body)), ProblemCode.CONCURRENT_MODIFICATION));
        assertThat(conflict.path("detail").asText()).isEqualTo("The profile was changed by another request. Reload it and try again.");
        expectProblem("PATCH", signedIn(patchJson(body)), ProblemCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    void unsupportedMethodsAreNotAllowed() throws Exception {
        expectProblem("PUT", signedIn(put(PATH)
                .contentType(MediaType.APPLICATION_JSON).content("{}")), ProblemCode.METHOD_NOT_ALLOWED);
    }

    private static MockHttpServletRequestBuilder patchJson(String body) {
        return patch(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(user(ID.toString())).with(csrf());
    }

    /** Performs the request; the response must have the status and conform to the contract. */
    private MockHttpServletResponse expect(String method, MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        String body = body(response);

        assertThat(response.getStatus()).as(body).isEqualTo(status);
        if (!"PUT".equals(method)) {
            assertThat(CONTRACT.validateResponse(PATH, method, status, response.getContentType(), body))
                    .as("%s %d vs openapi.yaml", method, status).isEmpty();
        }
        return response;
    }

    private MockHttpServletResponse expectProblem(String method, MockHttpServletRequestBuilder request, ProblemCode code)
            throws Exception {
        MockHttpServletResponse response = expect(method, request, code.status().value());

        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        assertThat(CONTRACT.validateSchema("ProblemDetail", body(response))).isEmpty();
        return response;
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(body(response));
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
