package com.casino.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.casino.Concurrency;
import com.casino.RealCsrfToken;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import com.casino.user.dto.UserRegistered;
import com.casino.wallet.service.WalletService;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /api/v1/auth/register} end to end (FR-1) with the real filter chain, Spring Session JDBC
 * and PostgreSQL. Runs with the embedded server, so the session cookie gets its configured
 * attributes; requests go through MockMvc with an explicit client address per test, so every test has
 * its own throttle counter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class RegistrationIT {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String PASSWORD = "correct-horse-battery-7f3a";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApplicationEvents events;

    /** The real service; one test makes it fail after it has written the wallet. */
    @MockitoSpyBean
    private WalletService walletService;

    /** A real token from GET /api/v1/auth/csrf (see {@link RealCsrfToken}), sent by {@link #register}. */
    private RealCsrfToken csrf;

    @BeforeEach
    void fetchCsrfToken() throws Exception {
        csrf = RealCsrfToken.fetch(mockMvc);
    }

    @AfterEach
    void deleteRows() {
        testDatabase.reset();
    }

    @Test
    void createsAPlayerAndSignsItIn() throws Exception {
        Instant before = Instant.now();
        MockHttpServletResponse response = perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.1"));
        Instant after = Instant.now();

        // AC-1.1: 201 with the profile.
        assertThat(response.getStatus()).as(content(response)).isEqualTo(201);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        JsonNode profile = json(response);
        UUID id = UUID.fromString(profile.path("id").asText());
        assertThat(profile.path("email").asText()).isEqualTo("alice@example.com");
        assertThat(profile.path("displayName").asText()).isEqualTo("Alice");
        assertThat(profile.path("role").asText()).isEqualTo("PLAYER");
        assertThat(profile.path("interests").isArray()).isTrue();
        assertThat(profile.path("interests")).isEmpty();
        // AC-1.2: the confirmation time comes from the server clock.
        assertThat(Instant.parse(profile.path("adultConfirmedAt").asText())).isBetween(before.minusMillis(1), after);
        assertThat(profile.has("createdAt")).isTrue();
        // Never the password or its hash; personal data is not cached.
        assertThat(content(response)).doesNotContain(PASSWORD, "{bcrypt}", "password");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        // Stored as a PLAYER in status ACTIVE, with only a BCrypt hash (AC-1.6, AC-1.7).
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id);
        assertThat(row).containsEntry("email", "alice@example.com").containsEntry("display_name", "Alice")
                .containsEntry("role", "PLAYER").containsEntry("status", "ACTIVE");
        assertThat(row.get("adult_confirmed_at")).isNotNull();
        assertThat(row.get("last_login_at")).isNull();
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(events.stream(UserRegistered.class)).containsExactly(new UserRegistered(id));

        // Signed in: an HttpOnly, SameSite=Lax session cookie for a stored session of this user.
        String setCookie = sessionCookie(response);
        assertThat(setCookie).contains("Path=/", "HttpOnly", "SameSite=Lax").doesNotContainIgnoringCase("Domain=");
        String sessionId = sessionId(setCookie);
        assertThat(jdbcTemplate.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?",
                String.class, sessionId)).isEqualTo(id.toString());
        // AC-5.5: the session contains neither the password nor its hash.
        for (byte[] attribute : jdbcTemplate.queryForList("""
                SELECT a.attribute_bytes FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id = a.session_primary_id WHERE s.session_id = ?""", byte[].class, sessionId)) {
            assertThat(new String(attribute, StandardCharsets.ISO_8859_1)).doesNotContain(PASSWORD, "{bcrypt}", hash);
        }
        // The session authenticates: an unknown protected path is 404 instead of 401.
        assertThat(perform(get("/api/v1/wallet").cookie(new Cookie("SESSION", cookieValue(setCookie)))).getStatus())
                .isEqualTo(404);
    }

    @Test
    void aNewPlayerGetsExactlyOneWalletWithTheStartingBalanceInTheRegistrationTransaction() throws Exception {
        MockHttpServletResponse response = perform(register(body("wallet@example.com", PASSWORD, "Wally", true), "192.0.2.13"));

        assertThat(response.getStatus()).as(content(response)).isEqualTo(201);
        UUID id = UUID.fromString(json(response).path("id").asText());
        // W3-05 AC-1.1: one wallet with the default starting balance and one STARTING_BALANCE entry.
        Map<String, Object> wallet = jdbcTemplate.queryForMap("SELECT * FROM wallets WHERE user_id = ?", id);
        assertThat(wallet).containsEntry("balance", 1000L);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM wallet_transactions WHERE wallet_id = ?", wallet.get("id")))
                .singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("type", "STARTING_BALANCE").containsEntry("amount", 1000L)
                        .containsEntry("balance_after", 1000L).containsEntry("idempotency_key", "starting-balance")
                        .containsEntry("reference", null));
        assertThat(walletRows()).isEqualTo("1 wallets, 1 entries");
        // One physical transaction: PostgreSQL stamped the account, the wallet (its latest row version)
        // and the entry with the same transaction id.
        assertThat(jdbcTemplate.queryForList("""
                SELECT xmin::text FROM users WHERE id = ?
                UNION SELECT xmin::text FROM wallets WHERE user_id = ?
                UNION SELECT t.xmin::text FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ?""",
                String.class, id, id, id)).hasSize(1);
        // AC-1.6: the response is the unchanged profile, without wallet data.
        assertThat(json(response).has("balance")).isFalse();
        assertThat(json(response).has("wallet")).isFalse();
    }

    @Test
    void aWalletFailureRollsTheWholeRegistrationBackWithoutSignIn() throws Exception {
        // W3-05 AC-1.2: the failure comes after the wallet and its entry were written. They are visible
        // inside the registration transaction (same connection), and none of the three rows may remain.
        List<String> insideTheTransaction = new ArrayList<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            insideTheTransaction.add(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class) + " users, " + walletRows());
            throw new IllegalStateException("simulated failure after the starting entry");
        }).when(walletService).openWallet(any());

        MockHttpServletResponse response = perform(register(body("doomed@example.com", PASSWORD, "Doomed", true), "192.0.2.15"));

        expectProblem(response, ProblemCode.INTERNAL_ERROR);
        assertThat(content(response)).doesNotContain("simulated", "wallet", "doomed");
        assertThat(insideTheTransaction).containsExactly("1 users, 1 wallets, 1 entries");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        assertThat(walletRows()).isEqualTo("0 wallets, 0 entries");
        // Not signed in: sign-in happens only after the account was created.
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION="));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL", Integer.class))
                .isZero();
    }

    @Test
    void failedRegistrationsCreateNoWallet() throws Exception {
        // W3-05 AC-1.3: a conflict, a validation error and a missing CSRF token leave the wallets alone.
        assertThat(perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.14")).getStatus()).isEqualTo(201);

        expectProblem(perform(register(body("alice@example.com", PASSWORD, "Alice 2", true), "192.0.2.14")),
                ProblemCode.EMAIL_ALREADY_REGISTERED);
        expectProblem(perform(register(body("bob@example.com", PASSWORD, "Bob", false), "192.0.2.14")), ProblemCode.VALIDATION_FAILED);
        expectProblem(perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(body("carol@example.com", PASSWORD, "Carol", true))),
                ProblemCode.CSRF_TOKEN_INVALID);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isOne();
        assertThat(walletRows()).isEqualTo("1 wallets, 1 entries");
    }

    @Test
    void theEmailIsTrimmedAndLowerCasedBeforeItIsCheckedAndStored() throws Exception {
        // AC-1.3
        MockHttpServletResponse response = perform(register(body("  Alice@Example.COM ", PASSWORD, "  Alice  ", true), "192.0.2.2"));

        assertThat(response.getStatus()).as(content(response)).isEqualTo(201);
        assertThat(json(response).path("email").asText()).isEqualTo("alice@example.com");
        assertThat(json(response).path("displayName").asText()).isEqualTo("Alice");
        assertThat(jdbcTemplate.queryForList("SELECT email FROM users", String.class)).containsExactly("alice@example.com");

        // A case variant is the same account.
        expectProblem(perform(register(body("ALICE@example.com", PASSWORD, "Alice 2", true), "192.0.2.2")),
                ProblemCode.EMAIL_ALREADY_REGISTERED);
    }

    @Test
    void aRegisteredEmailIsAConflictWithoutSignIn() throws Exception {
        assertThat(perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.3")).getStatus()).isEqualTo(201);

        MockHttpServletResponse duplicate = perform(register(body("alice@example.com", "another-password-1234", "Mallory", true),
                "192.0.2.3"));

        // AC-1.4
        expectProblem(duplicate, ProblemCode.EMAIL_ALREADY_REGISTERED);
        assertThat(json(duplicate).path("detail").asText()).isEqualTo("An account with this email already exists.");
        assertThat(duplicate.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION="));
        assertThat(jdbcTemplate.queryForList("SELECT display_name FROM users", String.class)).containsExactly("Alice");
    }

    @Test
    void concurrentRegistrationsOfOneEmailCreateExactlyOneAccount() throws Exception {
        // 8 attempts: below the throttling limit of 10.
        List<Integer> statuses = Concurrency.runTogether(8,
                () -> perform(register(body("race@example.com", PASSWORD, "Racer", true), "192.0.2.12")).getStatus());

        // The loser of a race that passes the duplicate check is caught by uq_users_email: 409, never 500.
        assertThat(statuses).containsOnly(201, 409).filteredOn(status -> status == 201).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = 'race@example.com'", Integer.class))
                .isOne();
        // The losers' transactions rolled back before any wallet was opened.
        assertThat(walletRows()).isEqualTo("1 wallets, 1 entries");
    }

    @Test
    void invalidRequestsAreRejectedWithAllFieldErrorsAndNothingIsStored() throws Exception {
        record Case(String body, String... errors) {
        }
        // 254 is the limit; a well-formed address of 257 characters (labels of 61).
        String tooLongEmail = "alice@" + (("a".repeat(61) + ".").repeat(4)) + "com";
        List<Case> cases = List.of(
                new Case("{}", "adultConfirmed: must be true", "displayName: must not be blank", "email: must not be blank",
                        "password: must not be null"),
                new Case(body("not-an-email", PASSWORD, "Alice", true), "email: must be a valid email address"),
                new Case(body("alïce@example.com", PASSWORD, "Alice", true), "email: must be a valid email address"),
                new Case(body(tooLongEmail, PASSWORD, "Alice", true), "email: must be at most 254 characters long"),
                new Case(body("alice@example.com", PASSWORD, "Al", true), "displayName: must be 3 to 30 characters long"),
                new Case(body("alice@example.com", PASSWORD, "A".repeat(31), true), "displayName: must be 3 to 30 characters long"),
                new Case(body("alice@example.com", PASSWORD, "Ali\\u0007ce", true), "displayName: must not contain control characters"),
                // AC-1.2
                new Case(body("alice@example.com", PASSWORD, "Alice", false), "adultConfirmed: must be true"),
                new Case("{\"email\":\"alice@example.com\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Alice\"}",
                        "adultConfirmed: must be true"),
                // AC-1.5
                new Case(body("alice@example.com", "short-pass1", "Alice", true), "password: must be at least 12 characters long"),
                new Case(body("alice@example.com", "ä".repeat(37), "Alice", true), "password: must be at most 72 bytes long in UTF-8"),
                // W2-12
                new Case(body("alice@example.com", "Password1234", "Alice", true), "password: must not be a commonly used password"),
                new Case(body("alice@example.com", "ALICE@example.com", "Alice", true), "password: must not equal the email"),
                new Case(body("alice@example.com", "Alice Wonderland", "alice wonderland", true),
                        "password: must not equal the display name"));

        for (int i = 0; i < cases.size(); i++) {
            Case invalid = cases.get(i);
            // One client address per case: every attempt counts, also a 400.
            MockHttpServletResponse response = perform(register(invalid.body(), "198.51.100." + (i + 1)));

            expectProblem(response, ProblemCode.VALIDATION_FAILED);
            List<String> errors = new ArrayList<>();
            json(response).path("errors").forEach(error -> errors.add(error.path("field").asText() + ": " + error.path("message").asText()));
            // All errors in one response, sorted by field.
            assertThat(errors).as(invalid.body()).containsExactly(invalid.errors());
            // Rejected values are never echoed.
            assertThat(content(response)).doesNotContain(PASSWORD, "short-pass1", "Password1234", "not-an-email", "Wonderland");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        // Unreadable JSON
        expectProblem(perform(register("{\"email\":", "192.0.2.4")), ProblemCode.MALFORMED_REQUEST);
    }

    @Test
    void invalidAndUnreadableRequestsCountAsAttempts() throws Exception {
        // AC-1.8: every registration attempt counts, whatever its outcome: 5 × 400, 4 × 400 malformed, 1 × 201.
        for (int i = 0; i < 5; i++) {
            assertThat(perform(register(body("not-an-email", PASSWORD, "Alice", true), "192.0.2.13")).getStatus()).isEqualTo(400);
        }
        for (int i = 0; i < 4; i++) {
            assertThat(perform(register("{\"email\":", "192.0.2.13")).getStatus()).isEqualTo(400);
        }
        assertThat(perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.13")).getStatus()).isEqualTo(201);

        // The 11th attempt is throttled; the limit is checked before the body, so also an invalid one.
        for (String body : List.of(body("bob@example.com", PASSWORD, "Bob", true), body("not-an-email", "x", "B", false), "{")) {
            MockHttpServletResponse throttled = perform(register(body, "192.0.2.13"));
            expectProblem(throttled, ProblemCode.TOO_MANY_ATTEMPTS);
            assertThat(throttled.getHeader(HttpHeaders.RETRY_AFTER)).matches("[1-9][0-9]*");
        }
        assertThat(jdbcTemplate.queryForList("SELECT email FROM users", String.class)).containsExactly("alice@example.com");
    }

    @Test
    void concurrentInvalidAttemptsAreCountedExactly() throws Exception {
        // 30 parallel invalid requests from one address: exactly 10 are validated, 20 are throttled.
        List<Integer> statuses = Concurrency.runTogether(30,
                () -> perform(register(body("not-an-email", PASSWORD, "Alice", true), "192.0.2.14")).getStatus());

        assertThat(statuses).filteredOn(status -> status == 400).hasSize(10);
        assertThat(statuses).filteredOn(status -> status == 429).hasSize(20);
    }

    @Test
    void passwordsAtTheLimitsAreAccepted() throws Exception {
        // 12 characters; 72 bytes in UTF-8 (36 × 2-byte "ä"); 12 characters of 4 bytes each (48 bytes).
        List<String> passwords = List.of("x7Kq-vN2r-Pz", "ä".repeat(36), "😀".repeat(12));
        for (int i = 0; i < passwords.size(); i++) {
            MockHttpServletResponse response = perform(register(body("user" + i + "@example.com", passwords.get(i), "User " + i, true),
                    "192.0.2.5"));
            assertThat(response.getStatus()).as(content(response)).isEqualTo(201);
            String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class,
                    "user" + i + "@example.com");
            assertThat(passwordEncoder.matches(passwords.get(i), hash)).isTrue();
        }
    }

    @Test
    void extraPropertiesSuchAsTheRoleAreIgnored() throws Exception {
        // AC-1.7
        MockHttpServletResponse response = perform(register("""
                {"email":"mallory@example.com","password":"%s","displayName":"Mallory","adultConfirmed":true,
                 "role":"ADMIN","status":"DISABLED","id":"00000000-0000-0000-0000-000000000001",
                 "adultConfirmedAt":"2000-01-01T00:00:00Z"}""".formatted(PASSWORD), "192.0.2.6"));

        assertThat(response.getStatus()).as(content(response)).isEqualTo(201);
        assertThat(json(response).path("role").asText()).isEqualTo("PLAYER");
        assertThat(json(response).path("id").asText()).isNotEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(Instant.parse(json(response).path("adultConfirmedAt").asText())).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(jdbcTemplate.queryForMap("SELECT role, status FROM users"))
                .containsEntry("role", "PLAYER").containsEntry("status", "ACTIVE");
    }

    @Test
    void aCsrfTokenIsRequired() throws Exception {
        // AC-1.9
        expectProblem(perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(body("alice@example.com", PASSWORD, "Alice", true))), ProblemCode.CSRF_TOKEN_INVALID);
        expectProblem(perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(body("alice@example.com", PASSWORD, "Alice", true)).with(csrf.withWrongHeader())),
                ProblemCode.CSRF_TOKEN_INVALID);
        // The cookie alone is not enough: the header proves that the page could read it.
        expectProblem(perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(body("alice@example.com", PASSWORD, "Alice", true)).cookie(csrf.cookie())),
                ProblemCode.CSRF_TOKEN_INVALID);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        // Rejected before it is counted: a foreign page (which cannot send the header) cannot use up the
        // registration limit of its visitor's address.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM auth_throttle", Integer.class)).isZero();
    }

    @Test
    void theCompleteSpaFlowWorksOverHttpWithRealCookies() throws Exception {
        // A browser-like client over real HTTP: the cookie store accepts, sends and deletes cookies itself.
        CookieManager browser = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(browser).build()) {
            // 1. Start-up: the SPA fetches a token.
            assertThat(send(client, "GET", "/api/v1/auth/csrf", null, null).statusCode()).isEqualTo(204);
            String anonymousToken = cookie(browser, "XSRF-TOKEN");
            assertThat(anonymousToken).isNotNull();

            // 2. Registration with the token in the header (the cookie is sent by the store).
            HttpResponse<String> registered = send(client, "POST", REGISTER, anonymousToken,
                    body("alice@example.com", PASSWORD, "Alice", true));
            assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
            String userId = Json31.mapper().readTree(registered.body()).path("id").asText();
            // Signed in with a session that is stored in PostgreSQL; the anonymous token is deleted.
            String session = cookie(browser, "SESSION");
            assertThat(session).isNotNull();
            assertThat(jdbcTemplate.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?",
                    String.class, new String(Base64.getDecoder().decode(session), StandardCharsets.UTF_8))).isEqualTo(userId);
            // Deleted, and no new token in the same response (Tomcat writes Max-Age=0 as an Expires in 1970).
            assertThat(registered.headers().allValues(HttpHeaders.SET_COOKIE)).filteredOn(cookie -> cookie.startsWith("XSRF-TOKEN="))
                    .singleElement().satisfies(cookie -> assertThat(cookie).startsWith("XSRF-TOKEN=;")
                            .containsAnyOf("Max-Age=0", "Expires=Thu, 01 Jan 1970"));
            assertThat(cookie(browser, "XSRF-TOKEN")).isNull();

            // 3. Without a token the next unsafe request is rejected, also when signed in.
            HttpResponse<String> withoutToken = send(client, "POST", "/api/v1/wallet", null, "{}");
            assertThat(withoutToken.statusCode()).isEqualTo(403);
            assertThat(Json31.mapper().readTree(withoutToken.body()).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");

            // 4. The SPA fetches a new token, as the contract says; it is a different value.
            assertThat(send(client, "GET", "/api/v1/auth/csrf", null, null).statusCode()).isEqualTo(204);
            String signedInToken = cookie(browser, "XSRF-TOKEN");
            assertThat(signedInToken).isNotNull().isNotEqualTo(anonymousToken);

            // 5. The new token and the persisted session work together: CSRF passes and the user is
            // authenticated, so an unknown protected path is 404 (not 403 or 401).
            HttpResponse<String> signedIn = send(client, "POST", "/api/v1/wallet", signedInToken, "{}");
            assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(404);
        }
    }

    @Test
    void theAnonymousTokenIsNotBoundToTheSession() throws Exception {
        // Documents the stateless double-submit design (ADR-0006, csrf.spa()): the server keeps no token,
        // it compares the cookie with the header. A client that still presents the old value in both is
        // accepted. A foreign site cannot do this: it can neither read the cookie nor set the header.
        String session = cookieValue(sessionCookie(perform(register(body("alice@example.com", PASSWORD, "Alice", true),
                "192.0.2.15"))));

        MockHttpServletResponse withOldToken = perform(post("/api/v1/wallet").contentType(MediaType.APPLICATION_JSON).content("{}")
                .cookie(new Cookie("SESSION", session)).with(csrf));

        assertThat(withOldToken.getStatus()).isEqualTo(404);
    }

    @Test
    void aFreshClientRegistersWithTheTokenFromTheCsrfEndpointAndTheTokenIsRotated() throws Exception {
        // AC-4.3: the SPA flow with the real cookie and header, without test helpers.
        Cookie xsrf = perform(get("/api/v1/auth/csrf")).getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        MockHttpServletResponse response = perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(body("alice@example.com", PASSWORD, "Alice", true))
                .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()));

        assertThat(response.getStatus()).as(content(response)).isEqualTo(201);
        // The token used before sign-in is replaced or cleared; the SPA fetches a new one.
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).filteredOn(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .isNotEmpty()
                .noneMatch(cookie -> cookie.startsWith("XSRF-TOKEN=" + xsrf.getValue() + ";"));
    }

    @Test
    void signingInIssuesANewSessionId() throws Exception {
        String first = sessionCookie(perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.7")));

        // Session fixation: a request that arrives with an existing session gets a new id.
        MockHttpServletResponse second = perform(register(body("bob@example.com", PASSWORD, "Bob", true), "192.0.2.7")
                .cookie(new Cookie("SESSION", cookieValue(first))));

        String rotated = sessionCookie(second);
        assertThat(sessionId(rotated)).isNotEqualTo(sessionId(first));
        assertThat(jdbcTemplate.queryForList("SELECT session_id FROM spring_session", String.class))
                .contains(sessionId(rotated)).doesNotContain(sessionId(first));
        assertThat(jdbcTemplate.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?", String.class,
                sessionId(rotated))).isEqualTo(json(second).path("id").asText());
    }

    @Test
    void moreThanTenAttemptsPerIpAndHourAreThrottledIncludingConflicts() throws Exception {
        // AC-1.8: 1 success + 9 conflicts = 10 attempts; a forged X-Forwarded-For changes nothing.
        assertThat(perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.8")).getStatus()).isEqualTo(201);
        for (int i = 0; i < 9; i++) {
            MockHttpServletResponse conflict = perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.8")
                    .header("X-Forwarded-For", "203.0.113." + i));
            assertThat(conflict.getStatus()).isEqualTo(409);
        }

        MockHttpServletResponse throttled = perform(register(body("new@example.com", PASSWORD, "New", true), "192.0.2.8")
                .header("X-Forwarded-For", "203.0.113.99"));

        expectProblem(throttled, ProblemCode.TOO_MANY_ATTEMPTS);
        assertThat(Long.parseLong(throttled.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(3500L, 3600L);
        assertThat(throttled.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION="));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = 'new@example.com'", Integer.class)).isZero();
        // Only that address is throttled.
        assertThat(perform(register(body("new@example.com", PASSWORD, "New", true), "192.0.2.9")).getStatus()).isEqualTo(201);
    }

    @Test
    void otherMethodsAreNotAllowed() throws Exception {
        // Public path: 405 instead of 401.
        expectProblem(perform(get(REGISTER)), ProblemCode.METHOD_NOT_ALLOWED);
    }

    @Test
    void passwordsNeverAppearInTheLog(CapturedOutput output) throws Exception {
        String rejected = "leaked-password-0815-but-equals@example.com";
        perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.11"));
        perform(register(body("alice@example.com", PASSWORD, "Alice", true), "192.0.2.11"));
        perform(register(body(rejected, rejected, "Alice", true), "192.0.2.11"));

        assertThat(output.getAll()).doesNotContain(PASSWORD, rejected, "{bcrypt}");
    }

    // ------------------------------------------------------------------ helpers

    /** A real HTTP request; {@code csrfToken} (if any) goes into the header, cookies come from the client's store. */
    private HttpResponse<String> send(HttpClient client, String method, String path, String csrfToken, String json)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        }
        if (csrfToken != null) {
            request.header(RealCsrfToken.HEADER, csrfToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String cookie(CookieManager browser, String name) {
        return browser.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private String walletRows() {
        return jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM wallets) || ' wallets, ' || (SELECT count(*) FROM wallet_transactions) || ' entries'""",
                String.class);
    }

    private static String body(String email, String password, String displayName, boolean adultConfirmed) {
        return """
                {"email":"%s","password":"%s","displayName":"%s","adultConfirmed":%s}"""
                .formatted(email, password, displayName, adultConfirmed);
    }

    /** A registration with a valid CSRF token from {@code clientIp}. */
    private MockHttpServletRequestBuilder register(String body, String clientIp) {
        return post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(body).with(csrf)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                });
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String content(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(content(response));
    }

    /** Status and code, Problem Details per contract, {@code no-store}. */
    private static void expectProblem(MockHttpServletResponse response, ProblemCode code) throws Exception {
        String body = content(response);
        assertThat(response.getStatus()).as(body).isEqualTo(code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        assertThat(body).doesNotContain("Exception", "org.springframework", "com.casino", "uq_users_email", "SQL");
    }

    private static String sessionCookie(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith("SESSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in " + response.getHeaders(HttpHeaders.SET_COOKIE)));
    }

    private static String cookieValue(String setCookie) {
        int end = setCookie.indexOf(';');
        return setCookie.substring(setCookie.indexOf('=') + 1, end < 0 ? setCookie.length() : end);
    }

    /** Spring Session stores the session id Base64-encoded in the cookie. */
    private static String sessionId(String setCookie) {
        return new String(Base64.getDecoder().decode(cookieValue(setCookie)), StandardCharsets.UTF_8);
    }
}
