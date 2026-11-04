package com.casino.auth.config;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role-based access (FR-9, AC-9.2–AC-9.4) with the real filter chain, Spring Session JDBC and
 * PostgreSQL. {@code /api/v1/admin/**} has no production endpoint before Week 10, so a test-only
 * endpoint ({@link AdminProbe}, test sources only, not in the contract) stands in for one. The
 * administrator is the one {@link AdminBootstrapRunner} creates from the configured values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.bootstrap.admin.email=" + AdminAuthorizationIT.ADMIN_EMAIL,
        "app.bootstrap.admin.password=" + AdminAuthorizationIT.ADMIN_PASSWORD})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AdminAuthorizationIT.AdminProbe.class})
class AdminAuthorizationIT {

    static final String ADMIN_EMAIL = "admin@example.com";
    static final String ADMIN_PASSWORD = "bootstrap-admin-pass-4b8d";
    private static final String PLAYER_PASSWORD = "correct-horse-battery-2c6a";
    private static final String PROBE = "/api/v1/admin/test/probe";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminBootstrapRunner bootstrap;

    private TestAccounts accounts;
    private RealCsrfToken csrf;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        // Created at start-up; recreated here after the previous test deleted it (idempotent otherwise).
        bootstrap.run(null);
        csrf = RealCsrfToken.fetch(mockMvc);
    }

    @AfterEach
    void deleteRows() {
        accounts.deleteAll();
    }

    @Test
    void theBootstrapAdministratorExistsOnceAndCanUseAdministrativeOperations() throws Exception {
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, ADMIN_EMAIL);
        bootstrap.run(null);
        // AC-9.4: idempotent, the password is not reset.
        assertThat(jdbcTemplate.queryForList("SELECT role FROM users", String.class)).containsExactly("ADMIN");
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo(hash)
                .startsWith("{bcrypt}$2a$12$").doesNotContain(ADMIN_PASSWORD);

        MockHttpServletResponse login = perform(login(ADMIN_EMAIL, ADMIN_PASSWORD, "192.0.2.70"));
        assertThat(login.getStatus()).isEqualTo(200);
        assertThat(json(login).path("role").asText()).isEqualTo("ADMIN");
        Cookie admin = cookie(sessionCookie(login));

        assertThat(perform(get(PROBE).cookie(admin)).getContentAsString()).isEqualTo("{\"access\":\"granted\"}");
        assertThat(perform(post(PROBE).cookie(admin).with(csrf)).getStatus()).isEqualTo(200);
        // CSRF applies to administrators too.
        expectProblem(perform(post(PROBE).cookie(admin)), ProblemCode.CSRF_TOKEN_INVALID);
        // An unknown administrative path is simply unknown for an administrator.
        expectProblem(perform(get("/api/v1/admin/unknown").cookie(admin)), ProblemCode.NOT_FOUND);
        // Administrators use the self-service operations like everyone else.
        MockHttpServletResponse profile = perform(get("/api/v1/users/me").cookie(admin));
        assertThat(json(profile).path("role").asText()).isEqualTo("ADMIN");
        assertThat(json(profile).path("displayName").asText()).isEqualTo("Administrator");
    }

    @Test
    void anonymousRequestsToAdministrativePathsAreUnauthenticated() throws Exception {
        int sessions = sessionCount();

        for (MockHttpServletRequestBuilder request : List.of(get(PROBE), post(PROBE).with(csrf), get("/api/v1/admin"),
                get("/api/v1/admin/unknown"))) {
            MockHttpServletResponse response = perform(request);
            expectProblem(response, ProblemCode.AUTHENTICATION_REQUIRED);
            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(header -> header.startsWith("SESSION="));
        }
        assertThat(sessionCount()).isEqualTo(sessions);

        // An ended session is reported as such.
        Cookie admin = signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(perform(post("/api/v1/auth/logout").cookie(admin).with(csrf)).getStatus()).isEqualTo(204);
        expectProblem(perform(get(PROBE).cookie(admin)), ProblemCode.SESSION_EXPIRED);
    }

    @Test
    void playersAreForbiddenEvenWhenTheyClaimTheAdministratorRole() throws Exception {
        // S9: registration, login and profile ignore a client-supplied role.
        MockHttpServletResponse registered = perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"mallory@example.com","password":"%s","displayName":"Mallory","adultConfirmed":true,
                         "role":"ADMIN","roles":["ADMIN"],"authorities":["ROLE_ADMIN"]}""".formatted(PLAYER_PASSWORD))
                .with(csrf).with(remoteAddr("192.0.2.71")));
        assertThat(registered.getStatus()).isEqualTo(201);
        assertThat(json(registered).path("role").asText()).isEqualTo("PLAYER");
        Cookie player = cookie(sessionCookie(registered));
        assertThat(perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}").cookie(player).with(csrf)).getStatus()).isEqualTo(200);
        MockHttpServletResponse login = perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"mallory@example.com\",\"password\":\"" + PLAYER_PASSWORD + "\",\"role\":\"ADMIN\"}")
                .with(csrf).with(remoteAddr("192.0.2.72")));
        assertThat(json(login).path("role").asText()).isEqualTo("PLAYER");
        Cookie relogged = cookie(sessionCookie(login));
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users WHERE email = 'mallory@example.com'", String.class))
                .isEqualTo("PLAYER");

        for (Cookie session : List.of(player, relogged)) {
            expectProblem(perform(get(PROBE).cookie(session)), ProblemCode.ACCESS_DENIED);
            expectProblem(perform(post(PROBE).cookie(session).with(csrf)), ProblemCode.ACCESS_DENIED);
            // Request values cannot grant the role.
            expectProblem(perform(get(PROBE).cookie(session).header("X-Role", "ADMIN").param("role", "ADMIN")),
                    ProblemCode.ACCESS_DENIED);
            // Unknown administrative paths are not distinguishable from existing ones.
            expectProblem(perform(get("/api/v1/admin/unknown").cookie(session)), ProblemCode.ACCESS_DENIED);
            expectProblem(perform(get("/api/v1/admin").cookie(session)), ProblemCode.ACCESS_DENIED);
        }
    }

    @Test
    void variantsOfAnAdministrativePathDoNotBypassTheRule() throws Exception {
        accounts.active("bob@example.com", PLAYER_PASSWORD);
        Cookie player = signIn("bob@example.com", PLAYER_PASSWORD);

        for (String path : List.of(PROBE + "/", "/api/v1/%61dmin/test/probe", "/api/v1/admin/./test/probe",
                "/api/v1/admin/test/../test/probe", "/api/v1/admin;x=1/test/probe", "/api/v1//admin/test/probe",
                "/api/v1/ADMIN/test/probe")) {
            MockHttpServletResponse response = perform(get(path).cookie(player));
            assertThat(response.getStatus()).as(path).isIn(400, 403, 404);
            assertThat(response.getContentAsString()).as(path).doesNotContain("granted");
        }
    }

    @Test
    void playersKeepTheirPermittedOperations() throws Exception {
        accounts.active("bob@example.com", PLAYER_PASSWORD);
        Cookie player = signIn("bob@example.com", PLAYER_PASSWORD);

        assertThat(perform(get("/api/v1/users/me").cookie(player)).getStatus()).isEqualTo(200);
        assertThat(perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"interests\":[\"PROBABILITY\"]}").cookie(player).with(csrf)).getStatus()).isEqualTo(200);
        assertThat(perform(get("/api/v1/interests").cookie(player)).getStatus()).isEqualTo(200);
        assertThat(perform(get("/api/v1/system/status").cookie(player)).getStatus()).isEqualTo(200);
        assertThat(perform(post("/api/v1/auth/logout").cookie(player).with(csrf)).getStatus()).isEqualTo(204);
    }

    @Test
    void theRoleIsTakenFromTheDatabaseAtSignInAndKeptForTheSession() throws Exception {
        // §7.5 (Week 2 scope): a role change in the database applies from the next sign-in; existing
        // sessions keep their role until they end. Revocation on role changes is required in Week 10.
        accounts.active("bob@example.com", PLAYER_PASSWORD);
        Cookie before = signIn("bob@example.com", PLAYER_PASSWORD);
        Cookie admin = signIn(ADMIN_EMAIL, ADMIN_PASSWORD);

        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE email = 'bob@example.com'");
        jdbcTemplate.update("UPDATE users SET role = 'PLAYER' WHERE email = ?", ADMIN_EMAIL);

        expectProblem(perform(get(PROBE).cookie(before)), ProblemCode.ACCESS_DENIED);
        assertThat(perform(get(PROBE).cookie(admin)).getStatus()).isEqualTo(200);

        assertThat(perform(get(PROBE).cookie(signIn("bob@example.com", PLAYER_PASSWORD))).getStatus()).isEqualTo(200);
        expectProblem(perform(get(PROBE).cookie(signIn(ADMIN_EMAIL, ADMIN_PASSWORD))), ProblemCode.ACCESS_DENIED);
    }

    // ------------------------------------------------------------------ helpers

    private Cookie signIn(String email, String password) throws Exception {
        MockHttpServletResponse response = perform(login(email, password, "192.0.2.73"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return cookie(sessionCookie(response));
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

    private int sessionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
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
        assertThat(body).doesNotContain("granted", "Exception", "com.casino", "ROLE_");
    }

    /** Test-only stand-in for the administrative endpoints of Week 10. Never part of the application. */
    @RestController
    @RequestMapping("/api/v1/admin/test/probe")
    static class AdminProbe {

        @GetMapping
        Map<String, String> read() {
            return Map.of("access", "granted");
        }

        @PostMapping
        Map<String, String> change() {
            return Map.of("access", "granted");
        }
    }
}
