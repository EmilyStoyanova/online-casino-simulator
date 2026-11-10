package com.casino.auth.controller;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static com.casino.SessionCookies.sessionId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * {@code POST /api/v1/auth/login} and {@code /logout} end to end (FR-2, FR-3, AC-5.4, AC-5.5) with the
 * real filter chain, Spring Session JDBC and PostgreSQL. Runs with the embedded server, so the
 * session cookie has its configured attributes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LoginLogoutIT {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String PROTECTED = "/api/v1/wallet";
    private static final String PASSWORD = "correct-horse-battery-9c1e";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

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
        testDatabase.reset();
    }

    @Test
    void loginSignsTheUserInWithAStoredSessionAndRecordsTheLogin() throws Exception {
        UUID id = accounts.active("alice@example.com", PASSWORD);
        Instant before = Instant.now();

        // The email is normalised as at registration.
        MockHttpServletResponse response = perform(login("  Alice@Example.COM ", PASSWORD, "192.0.2.30"));

        // AC-2.1: 200 with the profile.
        assertThat(response.getStatus()).as(content(response)).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        JsonNode profile = json(response);
        assertThat(profile.path("id").asText()).isEqualTo(id.toString());
        assertThat(profile.path("email").asText()).isEqualTo("alice@example.com");
        assertThat(profile.path("role").asText()).isEqualTo("PLAYER");
        assertThat(content(response)).doesNotContain(PASSWORD, "{bcrypt}", "password");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        // AC-2.4
        Timestamp lastLogin = jdbcTemplate.queryForObject("SELECT last_login_at FROM users WHERE id = ?", Timestamp.class, id);
        assertThat(lastLogin.toInstant()).isAfterOrEqualTo(before.minusMillis(1));

        // AC-5.1: the session cookie; AC-2.1: the XSRF-TOKEN cookie of the anonymous client is deleted.
        String setCookie = sessionCookie(response);
        assertThat(setCookie).contains("Path=/", "HttpOnly", "SameSite=Lax").doesNotContainIgnoringCase("Domain=");
        assertThat(response.getCookie(RealCsrfToken.COOKIE)).isNotNull()
                .satisfies(cookie -> assertThat(cookie.getMaxAge()).isZero());
        // AC-2.3: stored in PostgreSQL with the user id as principal; AC-5.5: no password or hash.
        String sessionId = sessionId(setCookie);
        assertThat(jdbcTemplate.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?",
                String.class, sessionId)).isEqualTo(id.toString());
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
        for (byte[] attribute : sessionAttributes(sessionId)) {
            assertThat(new String(attribute, StandardCharsets.ISO_8859_1)).doesNotContain(PASSWORD, "{bcrypt}", hash);
        }
        // Authenticated: an unknown protected path is 404 instead of 401.
        assertThat(perform(get(PROTECTED).cookie(cookie(setCookie))).getStatus()).isEqualTo(404);
    }

    @Test
    void loginIssuesANewSessionId() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        String first = sessionCookie(perform(login("alice@example.com", PASSWORD, "192.0.2.31")));

        // Session fixation: arriving with an existing session, the user gets a new id.
        String second = sessionCookie(perform(login("alice@example.com", PASSWORD, "192.0.2.31").cookie(cookie(first))));

        assertThat(sessionId(second)).isNotEqualTo(sessionId(first));
        assertThat(jdbcTemplate.queryForList("SELECT session_id FROM spring_session", String.class))
                .containsExactly(sessionId(second));
        expectProblem(perform(get(PROTECTED).cookie(cookie(first))), ProblemCode.SESSION_EXPIRED);
    }

    @Test
    void everyFailedLoginGetsTheSameAnswer() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        accounts.disabled("bob@example.com", PASSWORD);

        // AC-2.2, AC-5.4: unknown email, wrong password, disabled account (right or wrong password).
        List<MockHttpServletResponse> failures = List.of(
                perform(login("nobody@example.com", PASSWORD, "192.0.2.32")),
                perform(login("alice@example.com", "wrong-password-1234", "192.0.2.33")),
                perform(login("bob@example.com", PASSWORD, "192.0.2.34")),
                perform(login("bob@example.com", "wrong-password-1234", "192.0.2.35")),
                // Longer than BCrypt's 72 bytes: never a stored password.
                perform(login("alice@example.com", PASSWORD + "x".repeat(60), "192.0.2.36")));

        for (MockHttpServletResponse failure : failures) {
            expectProblem(failure, ProblemCode.INVALID_CREDENTIALS);
            assertThat(content(failure)).isEqualTo(content(failures.get(0)));
            assertThat(failure.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION="));
        }
        assertThat(json(failures.get(0)).path("detail").asText()).isEqualTo("The email or password is incorrect.");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT last_login_at FROM users", Timestamp.class)).containsOnlyNulls();
    }

    @Test
    void incompleteRequestsAreValidationErrors() throws Exception {
        MockHttpServletResponse response = perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}").with(csrf));

        expectProblem(response, ProblemCode.VALIDATION_FAILED);
        List<String> errors = json(response).findValuesAsText("field");
        assertThat(errors).containsExactly("email", "password");
        expectProblem(perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{\"email\":").with(csrf)),
                ProblemCode.MALFORMED_REQUEST);
    }

    @Test
    void logoutEndsTheSessionAndDeletesBothCookies() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        String setCookie = sessionCookie(perform(login("alice@example.com", PASSWORD, "192.0.2.37")));
        Cookie session = cookie(setCookie);

        MockHttpServletResponse response = perform(post(LOGOUT).cookie(session).with(csrf));

        // AC-3.1
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("SESSION=;").containsAnyOf("Max-Age=0", "Expires=Thu, 01 Jan 1970"));
        assertThat(response.getCookie(RealCsrfToken.COOKIE)).isNotNull()
                .satisfies(cookie -> assertThat(cookie.getMaxAge()).isZero());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId(setCookie))).isZero();
        // AC-3.2: the old cookie cannot be reused.
        expectProblem(perform(get(PROTECTED).cookie(session)), ProblemCode.SESSION_EXPIRED);
        // AC-3.3: idempotent, also with the old cookie or without any session, and creates no session.
        assertThat(perform(post(LOGOUT).cookie(session).with(csrf)).getStatus()).isEqualTo(204);
        MockHttpServletResponse anonymous = perform(post(LOGOUT).with(csrf));
        assertThat(anonymous.getStatus()).isEqualTo(204);
        assertThat(anonymous.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION=") && !cookie.startsWith("SESSION=;"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
    }

    @Test
    void logoutRequiresACsrfToken() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        Cookie session = cookie(sessionCookie(perform(login("alice@example.com", PASSWORD, "192.0.2.38"))));

        // AC-3.3: no forced logout from a foreign page.
        expectProblem(perform(post(LOGOUT).cookie(session)), ProblemCode.CSRF_TOKEN_INVALID);
        assertThat(perform(get(PROTECTED).cookie(session)).getStatus()).isEqualTo(404);
    }

    @Test
    void neitherPasswordsNorHashesNorSessionIdsAreLogged(CapturedOutput output) throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        String setCookie = sessionCookie(perform(login("alice@example.com", PASSWORD, "192.0.2.39")));
        perform(login("alice@example.com", "wrong-password-0815", "192.0.2.39"));
        perform(login("nobody@example.com", "unknown-user-pass-4711", "192.0.2.39"));
        perform(post(LOGOUT).cookie(cookie(setCookie)).with(csrf));

        assertThat(output.getAll()).doesNotContain(PASSWORD, "wrong-password-0815", "unknown-user-pass-4711", "{bcrypt}",
                sessionId(setCookie), cookie(setCookie).getValue());
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder login(String email, String password, String clientIp) {
        return post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(csrf)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                });
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private List<byte[]> sessionAttributes(String sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT a.attribute_bytes FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id = a.session_primary_id WHERE s.session_id = ?""", byte[].class, sessionId);
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
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        assertThat(body).doesNotContain("Exception", "org.springframework", "com.casino", "SQL");
    }
}
