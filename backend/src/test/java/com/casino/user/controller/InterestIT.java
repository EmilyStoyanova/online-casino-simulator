package com.casino.user.controller;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET /api/v1/interests} (AC-8.1) end to end with the catalogue seeded by V5: the documented
 * structure, the display order, public access with and without a session, and the standard errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InterestIT {

    private static final String INTERESTS = "/api/v1/interests";
    private static final String PASSWORD = "correct-horse-battery-3e8f";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    private static final List<String> SEEDED = List.of("PROBABILITY", "MATHEMATICS", "STATISTICS", "GAME_THEORY",
            "PSYCHOLOGY", "FINANCIAL_LITERACY", "HISTORY", "RESPONSIBLE_PLAY");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void deleteRows() {
        testDatabase.reset();
        jdbcTemplate.update("DELETE FROM interests WHERE code LIKE 'TEST\\_%'");
    }

    @Test
    void anonymousClientsGetTheSeededCatalogueInDisplayOrder() throws Exception {
        Integer sessionsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class);

        MockHttpServletResponse response = perform(get(INTERESTS));

        String body = content(response);
        assertThat(response.getStatus()).as(body).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(CONTRACT.validateResponse(INTERESTS, "GET", 200, response.getContentType(), body)).as(body).isEmpty();
        assertThat(codes(response)).containsExactlyElementsOf(SEEDED);
        // Exactly the DTO: no sort order, active flag or other columns of the table.
        JsonNode first = json(response).get(0);
        assertThat(fieldNames(first)).containsExactly("code", "name", "description");
        assertThat(first.path("name").asText()).isEqualTo("Probability & odds");
        assertThat(first.path("description").asText()).isEqualTo("How likely outcomes are and how odds are expressed");
        // Public and stateless: no session is created.
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION="));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isEqualTo(sessionsBefore);
    }

    @Test
    void theOrderIsStableAndInactiveInterestsAreNotListed() throws Exception {
        // Equal display orders are sorted by code; inactive interests are no longer offered.
        String insert = "INSERT INTO interests (code, name, description, sort_order, active) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(insert, "TEST_B", "Test B", "Second of equal order", 15, true);
        jdbcTemplate.update(insert, "TEST_A", "Test A", "First of equal order", 15, true);
        jdbcTemplate.update(insert, "TEST_RETIRED", "Retired", "No longer offered", 5, false);

        MockHttpServletResponse first = perform(get(INTERESTS));

        List<String> expected = new ArrayList<>(SEEDED);
        expected.addAll(1, List.of("TEST_A", "TEST_B"));
        assertThat(codes(first)).containsExactlyElementsOf(expected);
        assertThat(content(perform(get(INTERESTS)))).isEqualTo(content(first));
    }

    @Test
    void signedInUsersGetTheSameCatalogueAndAnEndedSessionDoesNotMatter() throws Exception {
        String anonymous = content(perform(get(INTERESTS)));
        new TestAccounts(jdbcTemplate, passwordEncoder).active("alice@example.com", PASSWORD);
        Cookie session = cookie(sessionCookie(perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\",\"password\":\"" + PASSWORD + "\"}")
                .with(RealCsrfToken.fetch(mockMvc)))));

        MockHttpServletResponse signedIn = perform(get(INTERESTS).cookie(session));
        assertThat(signedIn.getStatus()).isEqualTo(200);
        assertThat(content(signedIn)).isEqualTo(anonymous);

        // After logout the old cookie refers to no session: a public operation answers anyway (no 401).
        assertThat(perform(post("/api/v1/auth/logout").cookie(session).with(RealCsrfToken.fetch(mockMvc))).getStatus())
                .isEqualTo(204);
        MockHttpServletResponse ended = perform(get(INTERESTS).cookie(session));
        assertThat(ended.getStatus()).isEqualTo(200);
        assertThat(content(ended)).isEqualTo(anonymous);
    }

    @Test
    void otherRequestsGetStandardProblemDetails() throws Exception {
        // The catalogue is read-only: unsafe methods need a CSRF token first, then are not supported.
        expectProblem(perform(post(INTERESTS)), ProblemCode.CSRF_TOKEN_INVALID);
        expectProblem(perform(post(INTERESTS).with(RealCsrfToken.fetch(mockMvc))), ProblemCode.METHOD_NOT_ALLOWED);
        expectProblem(perform(get(INTERESTS).accept(MediaType.APPLICATION_XML)), ProblemCode.NOT_ACCEPTABLE);
        // Only the collection is public; anything below it is denied by default.
        expectProblem(perform(get(INTERESTS + "/PROBABILITY")), ProblemCode.AUTHENTICATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM interests", Integer.class)).isEqualTo(SEEDED.size());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static List<String> codes(MockHttpServletResponse response) throws Exception {
        List<String> codes = new ArrayList<>();
        json(response).forEach(interest -> codes.add(interest.path("code").asText()));
        return codes;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String content(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(content(response));
    }

    private static void expectProblem(MockHttpServletResponse response, ProblemCode code) throws Exception {
        String body = content(response);
        assertThat(response.getStatus()).as(body).isEqualTo(code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
    }
}
