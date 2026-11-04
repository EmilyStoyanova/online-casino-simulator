package com.casino.user.controller;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.casino.Concurrency;
import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestcontainersConfiguration;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * {@code GET/PATCH /api/v1/users/me} end to end (FR-7, FR-8, AC-9.3) with the real filter chain,
 * Spring Session JDBC and PostgreSQL: sessions from real logins, CSRF tokens from
 * {@code GET /api/v1/auth/csrf}, results checked in the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileIT {

    private static final String PROFILE = "/api/v1/users/me";
    private static final String PASSWORD = "correct-horse-battery-5d2f";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    /** The seeded catalogue (V5) in display order. */
    private static final List<String> CATALOGUE = List.of("PROBABILITY", "MATHEMATICS", "STATISTICS", "GAME_THEORY",
            "PSYCHOLOGY", "FINANCIAL_LITERACY", "HISTORY", "RESPONSIBLE_PLAY");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestAccounts accounts;
    private RealCsrfToken csrf;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        csrf = RealCsrfToken.fetch(mockMvc);
    }

    @AfterEach
    void deleteRows() {
        accounts.deleteAll();
        jdbcTemplate.update("UPDATE interests SET active = TRUE");
    }

    @Test
    void theProfileAfterRegistrationIsTheRegisteredOne() throws Exception {
        MockHttpServletResponse registered = perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"alice@example.com","password":"%s","displayName":"Alice","adultConfirmed":true}"""
                        .formatted(PASSWORD))
                .with(csrf).with(request -> {
                    request.setRemoteAddr("192.0.2.60");
                    return request;
                }));
        assertThat(registered.getStatus()).isEqualTo(201);
        Cookie session = cookie(sessionCookie(registered));

        // AC-7.1: the start-up check of the SPA restores exactly this profile.
        MockHttpServletResponse profile = expect("GET", get(PROFILE).cookie(session), 200);

        assertThat(json(profile)).isEqualTo(json(registered));
        assertThat(json(profile).path("interests").isEmpty()).isTrue();
        assertThat(content(profile)).doesNotContain("password", "bcrypt", "version", "status", "lastLogin");
        assertThat(profile.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void anonymousAndEndedSessionsAreRejectedWithoutCreatingASession() throws Exception {
        MockHttpServletResponse anonymous = expectProblem(get(PROFILE), ProblemCode.AUTHENTICATION_REQUIRED);
        assertThat(anonymous.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(header -> header.startsWith("SESSION="));
        expectProblem(patchJson("{\"displayName\":\"Mallory\"}").with(csrf), ProblemCode.AUTHENTICATION_REQUIRED);

        accounts.active("alice@example.com", PASSWORD);
        Cookie session = signIn("alice@example.com");
        assertThat(perform(post("/api/v1/auth/logout").cookie(session).with(csrf)).getStatus()).isEqualTo(204);

        expectProblem(get(PROFILE).cookie(session), ProblemCode.SESSION_EXPIRED);
        expectProblem(patchJson("{\"displayName\":\"Mallory\"}").cookie(session).with(csrf), ProblemCode.SESSION_EXPIRED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
    }

    @Test
    void updatesTheDisplayNameAndTheInterestsAndKeepsEverythingElse() throws Exception {
        UUID id = accounts.active("alice@example.com", PASSWORD);
        Cookie session = signIn("alice@example.com");
        Map<String, Object> before = protectedColumns(id);

        // AC-7.3 / S9: mass assignment of protected properties is ignored.
        MockHttpServletResponse updated = expect("PATCH", patchJson("""
                {"displayName":"  Alice B.  ","interests":["STATISTICS","PROBABILITY"],"email":"mallory@example.com",
                 "role":"ADMIN","status":"DISABLED","adultConfirmedAt":"2000-01-01T00:00:00Z","passwordHash":"x",
                 "id":"%s"}""".formatted(UUID.randomUUID())).cookie(session).with(csrf), 200);

        JsonNode profile = json(updated);
        assertThat(profile.path("id").asText()).isEqualTo(id.toString());
        assertThat(profile.path("displayName").asText()).isEqualTo("Alice B.");
        assertThat(profile.path("interests").toString()).isEqualTo("[\"PROBABILITY\",\"STATISTICS\"]");
        assertThat(profile.path("email").asText()).isEqualTo("alice@example.com");
        assertThat(profile.path("role").asText()).isEqualTo("PLAYER");
        assertThat(protectedColumns(id)).isEqualTo(before);
        assertThat(displayName(id)).isEqualTo("Alice B.");
        assertThat(selection(id)).containsExactly("PROBABILITY", "STATISTICS");

        // Omitted properties stay; the interests replace the whole selection.
        expect("PATCH", patchJson("{\"interests\":[\"MATHEMATICS\"]}").cookie(session).with(csrf), 200);
        assertThat(displayName(id)).isEqualTo("Alice B.");
        assertThat(selection(id)).containsExactly("MATHEMATICS");
        // Read back, also after a new login.
        assertThat(json(expect("GET", get(PROFILE).cookie(session), 200)).path("interests").toString()).isEqualTo("[\"MATHEMATICS\"]");
        JsonNode login = json(perform(login("alice@example.com", "192.0.2.61")));
        assertThat(login.path("displayName").asText()).isEqualTo("Alice B.");
        assertThat(login.path("interests").toString()).isEqualTo("[\"MATHEMATICS\"]");

        expect("PATCH", patchJson("{\"interests\":[]}").cookie(session).with(csrf), 200);
        assertThat(selection(id)).isEmpty();
    }

    @Test
    void allEightInterestsCanBeSelected() throws Exception {
        UUID id = accounts.active("alice@example.com", PASSWORD);
        Cookie session = signIn("alice@example.com");

        MockHttpServletResponse response = expect("PATCH", patchJson("{\"interests\":" + toJson(CATALOGUE.reversed()) + "}")
                .cookie(session).with(csrf), 200);

        // Returned in catalogue order, whatever the order of the request.
        assertThat(json(response).path("interests").toString()).isEqualTo(toJson(CATALOGUE));
        assertThat(selection(id)).hasSize(8);
    }

    @Test
    void invalidSelectionsAreRejectedAndNothingIsSaved() throws Exception {
        UUID id = accounts.active("alice@example.com", PASSWORD);
        Cookie session = signIn("alice@example.com");
        expect("PATCH", patchJson("{\"interests\":[\"PROBABILITY\"]}").cookie(session).with(csrf), 200);
        jdbcTemplate.update("UPDATE interests SET active = FALSE WHERE code = 'HISTORY'");

        List<String> nine = new ArrayList<>(CATALOGUE);
        nine.add("ASTROLOGY");
        // Each request has a valid display name and an invalid selection (or the other way round).
        Map<List<String>, String> cases = Map.of(
                List.of("MATHEMATICS", "ASTROLOGY"), "interests[1]",
                // Deactivated: no longer offered.
                List.of("HISTORY"), "interests[0]",
                CATALOGUE, "interests[6]",
                List.of("MATHEMATICS", "MATHEMATICS"), "interests",
                nine, "interests");

        for (Map.Entry<List<String>, String> entry : cases.entrySet()) {
            String body = "{\"displayName\":\"Mallory\",\"interests\":" + toJson(entry.getKey()) + "}";
            JsonNode problem = json(expectProblem(patchJson(body).cookie(session).with(csrf), ProblemCode.VALIDATION_FAILED));
            assertThat(problem.path("errors").findValuesAsText("field")).as(body).containsExactly(entry.getValue());
            // Rejected codes are not echoed.
            assertThat(problem.toString()).doesNotContain("ASTROLOGY", "HISTORY");
        }
        expectProblem(patchJson("{\"displayName\":\"M\",\"interests\":[\"MATHEMATICS\"]}").cookie(session).with(csrf),
                ProblemCode.VALIDATION_FAILED);
        // AC-8.2: nothing of a rejected request is saved, not even its valid display name.
        assertThat(displayName(id)).isEqualTo("alice");
        assertThat(selection(id)).containsExactly("PROBABILITY");
    }

    @Test
    void aDeactivatedInterestDisappearsFromTheProfileAndGoesWithTheNextSelection() throws Exception {
        UUID id = accounts.active("alice@example.com", PASSWORD);
        Cookie session = signIn("alice@example.com");
        expect("PATCH", patchJson("{\"interests\":[\"HISTORY\",\"PROBABILITY\"]}").cookie(session).with(csrf), 200);

        jdbcTemplate.update("UPDATE interests SET active = FALSE WHERE code = 'HISTORY'");

        // The listed selection can be sent back unchanged.
        String listed = json(expect("GET", get(PROFILE).cookie(session), 200)).path("interests").toString();
        assertThat(listed).isEqualTo("[\"PROBABILITY\"]");
        expect("PATCH", patchJson("{\"displayName\":\"Alice B.\"}").cookie(session).with(csrf), 200);
        assertThat(selection(id)).containsExactly("HISTORY", "PROBABILITY");
        expect("PATCH", patchJson("{\"interests\":" + listed + "}").cookie(session).with(csrf), 200);
        assertThat(selection(id)).containsExactly("PROBABILITY");
    }

    @Test
    void usersCanOnlyReadAndChangeTheirOwnProfile() throws Exception {
        // AC-9.3: the user comes from the session; there is no way to address another user.
        UUID alice = accounts.active("alice@example.com", PASSWORD);
        UUID bob = accounts.active("bob@example.com", PASSWORD);
        Cookie aliceSession = signIn("alice@example.com");

        expect("PATCH", patchJson("{\"id\":\"%s\",\"displayName\":\"Mallory\",\"interests\":[\"HISTORY\"]}".formatted(bob))
                .cookie(aliceSession).with(csrf), 200);
        expectProblem(get(PROFILE + "/" + bob).cookie(aliceSession), ProblemCode.NOT_FOUND);
        expectProblem(get("/api/v1/users/" + bob).cookie(aliceSession), ProblemCode.NOT_FOUND);
        assertThat(perform(get(PROFILE).param("id", bob.toString()).cookie(aliceSession)).getContentAsString(StandardCharsets.UTF_8))
                .contains(alice.toString()).doesNotContain(bob.toString());

        assertThat(displayName(alice)).isEqualTo("Mallory");
        assertThat(displayName(bob)).isEqualTo("bob");
        assertThat(selection(bob)).isEmpty();
    }

    @Test
    void updatesWithoutAValidCsrfTokenChangeNothing() throws Exception {
        UUID id = accounts.active("alice@example.com", PASSWORD);
        Cookie session = signIn("alice@example.com");

        for (MockHttpServletRequestBuilder request : List.of(
                patchJson("{\"displayName\":\"Mallory\"}").cookie(session),
                patchJson("{\"displayName\":\"Mallory\"}").cookie(session).with(csrf.withWrongHeader()),
                patchJson("{\"displayName\":\"Mallory\"}").cookie(session, csrf.cookie()))) {
            expectProblem(request, ProblemCode.CSRF_TOKEN_INVALID);
        }
        assertThat(displayName(id)).isEqualTo("alice");
    }

    @Test
    void overlappingUpdatesNeverOverwriteEachOtherOrFail() throws Exception {
        // AC-7.4: every request changes the name and the selection together. Requests that overlap get
        // 409; the others are applied one after the other. Never a 500, never a mix of two requests.
        UUID id = accounts.active("alice@example.com", PASSWORD);
        Cookie session = signIn("alice@example.com");
        AtomicInteger next = new AtomicInteger();

        List<Concurrency.Outcome<MockHttpServletResponse>> outcomes = Concurrency.outcomes(8, () -> {
            int i = next.getAndIncrement();
            return perform(patchJson("{\"displayName\":\"Racer " + i + "\",\"interests\":[\"" + CATALOGUE.get(i) + "\"]}")
                    .cookie(session).with(csrf));
        });

        int succeeded = 0;
        for (Concurrency.Outcome<MockHttpServletResponse> outcome : outcomes) {
            assertThat(outcome.failure()).isNull();
            MockHttpServletResponse response = outcome.result();
            if (response.getStatus() == 200) {
                succeeded++;
            } else {
                expectProblem(response, ProblemCode.CONCURRENT_MODIFICATION);
            }
        }
        assertThat(succeeded).isPositive();
        String name = displayName(id);
        int winner = Integer.parseInt(name.substring("Racer ".length()));
        assertThat(selection(id)).containsExactly(CATALOGUE.get(winner));
        // One version per applied request.
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isEqualTo(succeeded);
    }

    // ------------------------------------------------------------------ helpers

    private Cookie signIn(String email) throws Exception {
        return cookie(sessionCookie(perform(login(email, "192.0.2.62"))));
    }

    private MockHttpServletRequestBuilder login(String email, String clientIp) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .with(csrf)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                });
    }

    private static MockHttpServletRequestBuilder patchJson(String body) {
        return patch(PROFILE).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** Performs the request; the response must have the status and conform to the contract. */
    private MockHttpServletResponse expect(String method, MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).as(content(response)).isEqualTo(status);
        assertThat(CONTRACT.validateResponse(PROFILE, method, status, response.getContentType(), content(response)))
                .as("%s %d vs openapi.yaml", method, status).isEmpty();
        return response;
    }

    private MockHttpServletResponse expectProblem(MockHttpServletRequestBuilder request, ProblemCode code) throws Exception {
        MockHttpServletResponse response = perform(request);
        expectProblem(response, code);
        return response;
    }

    private static void expectProblem(MockHttpServletResponse response, ProblemCode code) throws Exception {
        String body = content(response);
        assertThat(response.getStatus()).as(body).isEqualTo(code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        assertThat(body).doesNotContain("Exception", "org.springframework", "com.casino", "SQL");
    }

    private Map<String, Object> protectedColumns(UUID id) {
        return jdbcTemplate.queryForMap("""
                SELECT email, password_hash, role, status, adult_confirmed_at, created_at FROM users WHERE id = ?""", id);
    }

    private String displayName(UUID id) {
        return jdbcTemplate.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, id);
    }

    private List<String> selection(UUID id) {
        return jdbcTemplate.queryForList(
                "SELECT interest_code FROM user_interests WHERE user_id = ? ORDER BY interest_code", String.class, id);
    }

    private static String toJson(List<String> codes) {
        return codes.stream().map(code -> "\"" + code + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private static String content(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(content(response));
    }
}
