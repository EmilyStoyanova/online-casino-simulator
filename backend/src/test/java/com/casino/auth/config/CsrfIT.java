package com.casino.auth.config;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CSRF protection of every unsafe operation and the token lifecycle around sign-in and sign-out
 * (FR-4, S8) with the SPA flow: {@code GET /auth/csrf}, then cookie + {@code X-XSRF-TOKEN} header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CsrfIT {

    private static final String PASSWORD = "correct-horse-battery-1a7c";
    private static final String LOGIN_BODY = "{\"email\":\"alice@example.com\",\"password\":\"" + PASSWORD + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestAccounts accounts;

    @BeforeEach
    void createAccount() {
        accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        accounts.active("alice@example.com", PASSWORD);
    }

    @AfterEach
    void deleteRows() {
        testDatabase.reset();
    }

    @Test
    void everyUnsafeOperationRequiresAMatchingToken() throws Exception {
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        Cookie session = cookie(sessionCookie(perform(json(post("/api/v1/auth/login"), LOGIN_BODY).with(csrf))));
        // Suppliers: request builders are mutable, every check needs a fresh one.
        List<Supplier<MockHttpServletRequestBuilder>> unsafe = List.of(
                () -> json(post("/api/v1/auth/register"), "{\"email\":\"bob@example.com\",\"password\":\"" + PASSWORD
                        + "\",\"displayName\":\"Bob\",\"adultConfirmed\":true}"),
                // AC-4.2: login CSRF (signing a victim in to the attacker's account) is prevented too.
                () -> json(post("/api/v1/auth/login"), LOGIN_BODY),
                // No forced logout.
                () -> post("/api/v1/auth/logout").cookie(session),
                // Protected operations: CSRF is checked before anything else.
                () -> json(patch("/api/v1/users/me"), "{\"displayName\":\"Mallory\"}").cookie(session),
                () -> post("/api/v1/system/status"));

        for (Supplier<MockHttpServletRequestBuilder> request : unsafe) {
            // No token, a wrong header, the cookie only (a foreign page cannot read it to send the header).
            expectCsrfRejected(perform(request.get()));
            expectCsrfRejected(perform(request.get().with(csrf.withWrongHeader())));
            expectCsrfRejected(perform(request.get().cookie(csrf.cookie())));
        }
        // Nothing happened: no account, no throttling attempt, the session is still signed in.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM auth_throttle", Integer.class)).isZero();
        assertThat(perform(get("/api/v1/wallet").cookie(session)).getStatus()).isEqualTo(404);
    }

    @Test
    void loginAndLogoutDeleteTheTokenAndTheSpaFetchesANewOne() throws Exception {
        // AC-4.3 (revised in W2-08)
        RealCsrfToken anonymous = RealCsrfToken.fetch(mockMvc);
        MockHttpServletResponse login = perform(json(post("/api/v1/auth/login"), LOGIN_BODY).with(anonymous));
        assertThat(login.getStatus()).isEqualTo(200);
        expectTokenDeleted(login);
        Cookie session = cookie(sessionCookie(login));

        RealCsrfToken signedIn = RealCsrfToken.fetch(mockMvc);
        assertThat(signedIn.cookie().getValue()).isNotEqualTo(anonymous.cookie().getValue());
        MockHttpServletResponse logout = perform(post("/api/v1/auth/logout").cookie(session).with(signedIn));
        assertThat(logout.getStatus()).isEqualTo(204);
        expectTokenDeleted(logout);

        RealCsrfToken signedOut = RealCsrfToken.fetch(mockMvc);
        assertThat(signedOut.cookie().getValue()).isNotEqualTo(signedIn.cookie().getValue());
        assertThat(perform(json(post("/api/v1/auth/login"), LOGIN_BODY).with(signedOut)).getStatus()).isEqualTo(200);
    }

    @Test
    void thePreviousTokenIsNotRevokedOnTheServer() throws Exception {
        // AC-4.4 (revised in W2-08): stateless double-submit (ADR-0006, csrf.spa()). The server keeps no
        // token; it compares the cookie with the header. Deleting the cookie in the browser is the whole
        // "rotation": a client that still sends the old value in both places is accepted. A foreign page
        // can neither read the cookie nor set the header, so this does not help an attacker.
        RealCsrfToken old = RealCsrfToken.fetch(mockMvc);
        Cookie session = cookie(sessionCookie(perform(json(post("/api/v1/auth/login"), LOGIN_BODY).with(old))));

        assertThat(perform(post("/api/v1/auth/logout").cookie(session).with(old)).getStatus()).isEqualTo(204);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void expectCsrfRejected(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body).isEqualTo(403);
        assertThat(Json31.mapper().readTree(body).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");
    }

    private static void expectTokenDeleted(MockHttpServletResponse response) {
        assertThat(response.getCookie(RealCsrfToken.COOKIE)).isNotNull()
                .satisfies(cookie -> assertThat(cookie.getMaxAge()).isZero());
    }
}
