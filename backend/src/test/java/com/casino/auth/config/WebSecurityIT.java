package com.casino.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.casino.TestcontainersConfiguration;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The security foundation end to end, with the real filter chain (Spring Session JDBC on PostgreSQL,
 * Spring Security): access rules, JSON 401/403, CSRF, session cookie and timeout, security headers,
 * CORS and the password encoder.
 *
 * <p>Runs with the embedded server: Spring Boot applies the {@code server.servlet.session.cookie.*}
 * settings to Spring Session's cookie only then, as in production. MockMvc uses the same context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, WebSecurityIT.SessionProbe.class})
class WebSecurityIT {

    /** Test-only endpoint that creates a session; nothing in W2-04 signs users in yet. */
    static final String SESSION_PROBE = "/api/v1/test/session";
    private static final String PROTECTED = "/api/v1/wallet";
    private static final String DEV_ORIGIN = "http://localhost:5173";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    private static final List<String> INTERNALS = List.of(
            "Exception", "org.springframework", "com.casino", "Full authentication", "\tat ");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void publicEndpointsNeedNoSession() throws Exception {
        for (String path : List.of("/api/v1/system/status", "/api/v1/interests", "/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/info", "/openapi.yaml", "/v3/api-docs",
                "/v3/api-docs/swagger-config", "/swagger-ui/index.html")) {
            assertThat(perform(mockMvc, get(path)).getStatus()).as("GET %s", path).isEqualTo(200);
            assertThat(perform(mockMvc, head(path)).getStatus()).as("HEAD %s", path).isEqualTo(200);
        }
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent());
    }

    @Test
    void unsupportedMethodsOnPublicPathsAnswer405() throws Exception {
        for (String path : List.of("/api/v1/system/status", "/api/v1/auth/csrf", "/actuator/health", "/openapi.yaml")) {
            expectProblem(perform(mockMvc, post(path).with(csrf())), ProblemCode.METHOD_NOT_ALLOWED);
        }
    }

    @Test
    void protectedApiPathsRequireASession() throws Exception {
        int sessionsBefore = sessionCount();

        MockHttpServletResponse anonymous = perform(mockMvc, get(PROTECTED).param("page", "1"));

        expectProblem(anonymous, ProblemCode.AUTHENTICATION_REQUIRED);
        // Same Problem Details as every other error: the request path without query string.
        assertThat(Json31.mapper().readTree(anonymous.getContentAsString()).path("instance").asText()).isEqualTo(PROTECTED);
        // A rejected anonymous request creates no session (no saved request). The XSRF-TOKEN cookie may
        // be issued: csrf.spa() renders it on every response, without a session.
        assertThat(anonymous.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION="));
        assertThat(sessionCount()).isEqualTo(sessionsBefore);
        // Authenticated, the path is simply unknown.
        mockMvc.perform(get(PROTECTED).with(user("player"))).andExpect(status().isNotFound());
    }

    @Test
    void everythingElseIsDeniedByDefault() throws Exception {
        expectProblem(perform(mockMvc, get("/internal/metrics")), ProblemCode.AUTHENTICATION_REQUIRED);
        expectProblem(perform(mockMvc, get("/internal/metrics").with(user("player"))), ProblemCode.ACCESS_DENIED);
    }

    @Test
    void requestWithAnEndedSessionIsReportedAsExpired() throws Exception {
        Cookie session = new Cookie("SESSION", cookieValue(createSession(mockMvc, "SESSION")));

        // The session exists but nobody is signed in to it.
        expectProblem(perform(mockMvc, get(PROTECTED).cookie(session)), ProblemCode.AUTHENTICATION_REQUIRED);
        // Removed from the store, as after an inactivity timeout or a logout.
        jdbcTemplate.update("DELETE FROM spring_session WHERE session_id = ?", sessionId(session.getValue()));
        expectProblem(perform(mockMvc, get(PROTECTED).cookie(session)), ProblemCode.SESSION_EXPIRED);
    }

    @Test
    void sessionCookieIsHttpOnlyLaxAndHostOnlyAndExpiresAfter30MinutesOfInactivity() throws Exception {
        String cookie = createSession(mockMvc, "SESSION");

        assertThat(cookie).contains("Path=/", "HttpOnly", "SameSite=Lax")
                .doesNotContainIgnoringCase("Domain=")
                .doesNotContain("Secure");
        assertThat(maxInactiveInterval(jdbcTemplate, cookie)).isEqualTo(30 * 60);
    }

    @Test
    void unsafeRequestsNeedAValidCsrfToken() throws Exception {
        expectProblem(perform(mockMvc, post("/api/v1/system/status")), ProblemCode.CSRF_TOKEN_INVALID);
        expectProblem(perform(mockMvc, post("/api/v1/system/status")
                .cookie(new Cookie("XSRF-TOKEN", "cookie-token")).header("X-XSRF-TOKEN", "forged-token")),
                ProblemCode.CSRF_TOKEN_INVALID);
        // CSRF is checked before authentication, also on protected paths.
        expectProblem(perform(mockMvc, delete(PROTECTED)), ProblemCode.CSRF_TOKEN_INVALID);

        // The SPA flow: fetch the token cookie, then send its value in the header.
        String xsrfCookie = setCookie(perform(mockMvc, get("/api/v1/auth/csrf")), "XSRF-TOKEN");
        assertThat(xsrfCookie).contains("Path=/").doesNotContain("HttpOnly");
        String token = cookieValue(xsrfCookie);
        MockHttpServletResponse accepted = perform(mockMvc, post("/api/v1/system/status")
                .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token));
        // The token is accepted; the method itself is not supported.
        expectProblem(accepted, ProblemCode.METHOD_NOT_ALLOWED);
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsStringIgnoringCase("no-store")));
    }

    @Test
    void corsAllowsTheFrontendOriginWithCredentialsAndTheCsrfHeader() throws Exception {
        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-XSRF-TOKEN, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEV_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsStringIgnoringCase("X-XSRF-TOKEN")));
        // Rejected safely: fixed plain-text 403, no CORS headers, nothing processed, nothing cached.
        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsStringIgnoringCase("no-store")))
                .andExpect(content().string("Invalid CORS request"));
        // Through the Vite proxy (changeOrigin) the browser still sends its Origin on unsafe requests.
        mockMvc.perform(post("/api/v1/system/status").header(HttpHeaders.ORIGIN, DEV_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEV_ORIGIN));
    }

    @Test
    void passwordsAreHashedWithBcryptCost12() {
        String hash = passwordEncoder.encode("correct-horse-battery");

        assertThat(hash).startsWith("{bcrypt}$2a$12$");
        assertThat(passwordEncoder.matches("correct-horse-battery", hash)).isTrue();
        assertThat(passwordEncoder.matches("correct-horse-batterY", hash)).isFalse();
    }

    /** Production-like cookie settings from environment variables (separate application context). */
    @Nested
    @TestPropertySource(properties = {
            "APP_SESSION_COOKIE_NAME=__Host-SESSION",
            "APP_SESSION_COOKIE_SECURE=true",
            "APP_SESSION_TIMEOUT=10m"})
    class WithProductionCookieSettings {

        @Autowired
        private MockMvc productionMockMvc;

        @Autowired
        private JdbcTemplate productionJdbcTemplate;

        @Test
        void sessionCookieUsesTheHostPrefixAndIsSecure() throws Exception {
            String cookie = createSession(productionMockMvc, "__Host-SESSION");

            // __Host- requires Secure, Path=/ and no Domain.
            assertThat(cookie).contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax").doesNotContainIgnoringCase("Domain=");
            assertThat(maxInactiveInterval(productionJdbcTemplate, cookie)).isEqualTo(10 * 60);
        }
    }

    // ------------------------------------------------------------------ helpers

    private int sessionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
    }

    /** Creates a session through the probe endpoint and returns its {@code Set-Cookie} header. */
    private static String createSession(MockMvc mockMvc, String cookieName) throws Exception {
        MockHttpServletResponse response = perform(mockMvc, get(SESSION_PROBE).with(user("player")));
        assertThat(response.getStatus()).isEqualTo(204);
        return setCookie(response, cookieName);
    }

    private static Integer maxInactiveInterval(JdbcTemplate jdbcTemplate, String setCookie) {
        return jdbcTemplate.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId(cookieValue(setCookie)));
    }

    private static String setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " cookie in " + response.getHeaders(HttpHeaders.SET_COOKIE)));
    }

    private static String cookieValue(String setCookie) {
        int end = setCookie.indexOf(';');
        return setCookie.substring(setCookie.indexOf('=') + 1, end < 0 ? setCookie.length() : end);
    }

    /** Spring Session stores the session id Base64-encoded in the cookie. */
    private static String sessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    private static MockHttpServletResponse perform(MockMvc mockMvc, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** Status and code, Problem Details per contract, {@code no-store} and no internal details. */
    private static void expectProblem(MockHttpServletResponse response, ProblemCode code) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isEqualTo(code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(Json31.mapper().readTree(body).path("code").asText()).isEqualTo(code.name());
        assertThat(body).doesNotContain(INTERNALS);
    }

    @RestController
    static class SessionProbe {

        @GetMapping(SESSION_PROBE)
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void createSession(HttpSession session) {
            session.setAttribute("probe", Boolean.TRUE);
        }
    }
}
