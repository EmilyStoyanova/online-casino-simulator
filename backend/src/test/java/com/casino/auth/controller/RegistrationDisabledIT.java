package com.casino.auth.controller;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Registration closed by configuration ({@code app.registration.enabled=false}, as on Render staging)
 * with the real filter chain, Spring Session JDBC and PostgreSQL: {@code 403 REGISTRATION_DISABLED},
 * while CSRF protection, login, logout and login throttling work as before. Open registration (the
 * default) is covered by {@link RegistrationIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.registration.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistrationDisabledIT {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String PASSWORD = "correct-horse-battery-5d1e";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    private static final String VALID_BODY = """
            {"email":"carol@example.com","password":"%s","displayName":"Carol","adultConfirmed":true}""".formatted(PASSWORD);

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
    void aValidRegistrationIsRejectedWithoutCreatingAnAccountASessionOrAnAttempt() throws Exception {
        int sessions = count("spring_session");

        MockHttpServletResponse response = perform(register(VALID_BODY).with(csrf).with(remoteAddr("192.0.2.80")));

        expectProblem(response, ProblemCode.REGISTRATION_DISABLED);
        assertThat(CONTRACT.validateResponse(REGISTER, "POST", 403, MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                response.getContentAsString(StandardCharsets.UTF_8))).isEmpty();
        assertThat(json(response).path("detail").asText()).isEqualTo("New registrations are currently closed.");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(header -> header.startsWith("SESSION="));
        assertThat(count("users")).isZero();
        assertThat(count("wallets")).isZero();
        assertThat(count("spring_session")).isEqualTo(sessions);
        // Not counted: closed registration cannot be used to fill the throttle table.
        assertThat(count("auth_throttle")).isZero();
    }

    @Test
    void theRequestCannotReopenRegistrationAndItsBodyIsNotRead() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                register(VALID_BODY).header("X-Registration-Enabled", "true").param("registrationEnabled", "true")
                        .param("app.registration.enabled", "true"),
                register("""
                        {"email":"dave@example.com","password":"%s","displayName":"Dave","adultConfirmed":true,
                         "registrationEnabled":true,"enabled":true}""".formatted(PASSWORD)),
                // Invalid or unreadable bodies get the same answer: the body is never read.
                register("{\"email\":\"not-an-email\"}"),
                register("{broken")}) {
            expectProblem(perform(request.with(csrf).with(remoteAddr("192.0.2.81"))), ProblemCode.REGISTRATION_DISABLED);
        }
        assertThat(count("users")).isZero();
    }

    @Test
    void csrfProtectionStillComesFirst() throws Exception {
        expectProblem(perform(register(VALID_BODY).with(remoteAddr("192.0.2.82"))), ProblemCode.CSRF_TOKEN_INVALID);
        assertThat(count("users")).isZero();
    }

    @Test
    void existingAccountsSignInAndOutAsBefore() throws Exception {
        accounts.active("erin@example.com", PASSWORD);

        MockHttpServletResponse login = perform(login("erin@example.com", PASSWORD, "192.0.2.83"));
        assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
        Cookie session = cookie(sessionCookie(login));
        assertThat(perform(get("/api/v1/users/me").cookie(session)).getStatus()).isEqualTo(200);

        csrf = RealCsrfToken.fetch(mockMvc);
        assertThat(perform(post("/api/v1/auth/logout").cookie(session).with(csrf)).getStatus()).isEqualTo(204);
        expectProblem(perform(get("/api/v1/users/me").cookie(session)), ProblemCode.SESSION_EXPIRED);
    }

    @Test
    void failedLoginsAreStillThrottled() throws Exception {
        accounts.active("frank@example.com", PASSWORD);

        for (int attempt = 1; attempt <= 5; attempt++) {
            expectProblem(perform(login("frank@example.com", "wrong-password-0000", "192.0.2.84")),
                    ProblemCode.INVALID_CREDENTIALS);
        }
        expectProblem(perform(login("frank@example.com", PASSWORD, "192.0.2.84")), ProblemCode.TOO_MANY_ATTEMPTS);
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder register(String body) {
        return post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder login(String email, String password, String clientIp) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(csrf).with(remoteAddr(clientIp));
    }

    private static RequestPostProcessor remoteAddr(String clientIp) {
        return request -> {
            request.setRemoteAddr(clientIp);
            return request;
        };
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void expectProblem(MockHttpServletResponse response, ProblemCode code) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body).isEqualTo(code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
    }
}
