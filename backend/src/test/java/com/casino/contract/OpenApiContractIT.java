package com.casino.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.common.exception.ProblemCode;
import com.casino.wallet.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * Verifies that the running application implements {@code openapi.yaml}:
 * <ol>
 *   <li>the specification springdoc generates from the controllers is structurally identical to
 *       the contract for all {@code /api/**} paths;</li>
 *   <li>every documented operation responds with a documented status, media type and body;</li>
 *   <li>the contract is served for Swagger UI.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractIT {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String PROFILE = "/api/v1/users/me";
    private static final String WALLET = "/api/v1/wallet";
    private static final String TRANSACTIONS = "/api/v1/wallet/transactions";
    private static final String SPIN = "/api/v1/games/roulette/spins";

    private final OpenApiContract contract = OpenApiContract.load();

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
    private WalletService walletService;

    @Test
    void implementationMatchesContractForAllApiPaths() throws Exception {
        String generated = perform(get("/v3/api-docs")).getContentAsString(StandardCharsets.UTF_8);

        assertThat(contract.differencesTo(OpenApiContract.fromJson(generated), path -> path.startsWith("/api/")))
                .as("Differences between openapi.yaml (contract) and the implementation")
                .isEmpty();
    }

    @Test
    void theDocumentationShowsExactlyTheProductionLedgerTypes() throws Exception {
        // W3-08: the published type is WalletEntryType (contract values only). W4-03: the roulette types
        // are implemented and the retired test-only types appear nowhere.
        String generated = perform(get("/v3/api-docs")).getContentAsString(StandardCharsets.UTF_8);
        String contractFile = perform(get("/openapi.yaml")).getContentAsString(StandardCharsets.UTF_8);

        for (String documentation : new String[] {generated, contractFile}) {
            assertThat(documentation).contains("STARTING_BALANCE", "ROULETTE_STAKE", "ROULETTE_PAYOUT")
                    .doesNotContain("TEST_CREDIT", "TEST_DEBIT");
        }
        assertThat(OpenApiContract.fromJson(generated).spec().getComponents().getSchemas().get("WalletTransactionType").getEnum())
                .map(String::valueOf).containsExactly("STARTING_BALANCE", "ROULETTE_STAKE", "ROULETTE_PAYOUT");
    }

    @Test
    void everyDocumentedOperationIsVerifiedByThisTest() {
        // A newly implemented operation must also get a runtime check below.
        assertThat(contract.implementedOperations()).containsExactlyInAnyOrder(
                "GET /api/v1/system/status",
                "GET /api/v1/auth/csrf",
                "POST /api/v1/auth/register",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/logout",
                "GET /api/v1/interests",
                "GET /api/v1/users/me",
                "PATCH /api/v1/users/me",
                "GET /api/v1/wallet",
                "GET /api/v1/wallet/transactions",
                "POST /api/v1/games/roulette/spins",
                "GET /actuator/health",
                "GET /actuator/health/liveness",
                "GET /actuator/health/readiness",
                "GET /actuator/info");
        // Planned operations cannot be verified at runtime (validateResponse rejects them). When one is
        // implemented, its marker is removed and it moves to the list above with a runtime check.
        // The spin designed in W4-02 is implemented in W4-07 (week-04.md §11): none is left.
        assertThat(contract.plannedOperations()).isEmpty();
    }

    @Test
    void profileRespondsAccordingToContract() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at)
                VALUES ('profile@example.com', ?, 'Profile', now())""", passwordEncoder.encode("correct-horse-battery"));
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        try {
            Cookie session = new Cookie("SESSION", perform(jsonPost(LOGIN,
                    "{\"email\":\"profile@example.com\",\"password\":\"correct-horse-battery\"}", "192.0.2.40").with(csrf))
                    .getCookie("SESSION").getValue());

            assertConformsToContract(PROFILE, get(PROFILE), 401);
            assertConformsToContract(PROFILE, get(PROFILE).cookie(session), 200);
            assertConformsToContract(PROFILE, "PATCH", profilePatch("{\"displayName\":\"Profile B.\"}").cookie(session), 403);
            assertConformsToContract(PROFILE, "PATCH", profilePatch("{\"displayName\":\"Profile B.\"}").with(csrf), 401);
            assertConformsToContract(PROFILE, "PATCH", profilePatch("""
                    {"displayName":"Profile B.","interests":["STATISTICS","PROBABILITY"]}""").cookie(session).with(csrf), 200);
            assertConformsToContract(PROFILE, "PATCH", profilePatch("{\"interests\":[\"ASTROLOGY\"]}").cookie(session).with(csrf), 400);
            assertConformsToContract(PROFILE, "PATCH", profilePatch("{\"displayName\":").cookie(session).with(csrf), 400);
            // 409 CONCURRENT_MODIFICATION: UserProfileControllerTest (contract) and UserProfileServiceIT (detection).

            assertThat(perform(post(LOGOUT).cookie(session).with(csrf)).getStatus()).isEqualTo(204);
            assertConformsToContract(PROFILE, get(PROFILE).cookie(session), 401);
        } finally {
            testDatabase.reset();
        }
    }

    @Test
    void walletRespondsAccordingToContract() throws Exception {
        TestAccounts accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        accounts.activePlayerWithWallet("wallet@example.com", "correct-horse-battery", walletService);
        accounts.active("no-wallet@example.com", "correct-horse-battery");
        accounts.activeAdministrator("wallet-admin@example.com", "correct-horse-battery");
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        try {
            assertConformsToContract(WALLET, get(WALLET), 401);
            MockHttpServletResponse wallet = assertConformsToContract(WALLET, get(WALLET).cookie(signIn("wallet@example.com", csrf)), 200);
            assertThat(wallet.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertConformsToContract(WALLET, get(WALLET).cookie(signIn("wallet-admin@example.com", csrf)), 403);
            // A player without a wallet: a data error, never a wallet created by the read (AC-2.6).
            assertConformsToContract(WALLET, get(WALLET).cookie(signIn("no-wallet@example.com", csrf)), 500);
        } finally {
            testDatabase.reset();
        }
    }

    @Test
    void rouletteSpinRespondsAccordingToContract() throws Exception {
        TestAccounts accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        accounts.activePlayerWithWallet("spin@example.com", "correct-horse-battery", walletService);
        accounts.activeAdministrator("spin-admin@example.com", "correct-horse-battery");
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        String bets = "{\"bets\":[{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":10},{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":20}]}";
        String key = "3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b";
        try {
            Cookie player = signIn("spin@example.com", csrf);
            MockHttpServletResponse played = assertConformsToContract(SPIN, "POST", spin(bets, key).cookie(player).with(csrf), 200);
            assertThat(played.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            // The replay is the same documented 200.
            assertThat(assertConformsToContract(SPIN, "POST", spin(bets, key).cookie(player).with(csrf), 200)
                    .getContentAsString(StandardCharsets.UTF_8)).isEqualTo(played.getContentAsString(StandardCharsets.UTF_8));
            assertConformsToContract(SPIN, "POST", spin("{\"bets\":[{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":1001}]}",
                    UUID.randomUUID().toString()).cookie(player).with(csrf), 400);
            assertConformsToContract(SPIN, "POST", spin(bets, "1-1-1-1-1").cookie(player).with(csrf), 400);
            assertConformsToContract(SPIN, "POST", spin("{\"bets\":[{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":10.5}]}",
                    UUID.randomUUID().toString()).cookie(player).with(csrf), 400);
            assertConformsToContract(SPIN, "POST", spin(bets, key).with(csrf), 401);
            assertConformsToContract(SPIN, "POST", spin(bets, UUID.randomUUID().toString()).cookie(player), 403);
            assertConformsToContract(SPIN, "POST", spin(bets, UUID.randomUUID().toString())
                    .cookie(signIn("spin-admin@example.com", csrf)).with(csrf), 403);
            // 409: other bets with the used key, and more coins than the wallet holds.
            assertConformsToContract(SPIN, "POST", spin("{\"bets\":[{\"category\":\"STRAIGHT\",\"number\":18,\"amount\":10}]}", key)
                    .cookie(player).with(csrf), 409);
            StringBuilder tenThousand = new StringBuilder("{\"bets\":[");
            for (int n = 1; n <= 10; n++) {
                tenThousand.append(n > 1 ? "," : "").append("{\"category\":\"STRAIGHT\",\"number\":").append(n).append(",\"amount\":1000}");
            }
            assertConformsToContract(SPIN, "POST", spin(tenThousand.append("]}").toString(), UUID.randomUUID().toString())
                    .cookie(player).with(csrf), 409);
            // 503 (lock timeout) and 500: RouletteServiceIT and RouletteControllerTest.
        } finally {
            testDatabase.reset();
        }
    }

    private static MockHttpServletRequestBuilder spin(String body, String idempotencyKey) {
        return post(SPIN).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", idempotencyKey);
    }

    @Test
    void walletTransactionsRespondAccordingToContract() throws Exception {
        TestAccounts accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        accounts.activePlayerWithWallet("history@example.com", "correct-horse-battery", walletService);
        accounts.active("no-history@example.com", "correct-horse-battery");
        accounts.activeAdministrator("history-admin@example.com", "correct-horse-battery");
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        try {
            Cookie player = signIn("history@example.com", csrf);
            assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS), 401);
            MockHttpServletResponse page = assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS).cookie(player), 200);
            assertThat(page.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS).param("limit", "1").cookie(player), 200);
            assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS).param("limit", "101").cookie(player), 400);
            assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS).param("limit", "ten").cookie(player), 400);
            assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS).param("cursor", "not-a-cursor").cookie(player), 400);
            assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS).cookie(signIn("history-admin@example.com", csrf)), 403);
            assertConformsToContract(TRANSACTIONS, get(TRANSACTIONS).cookie(signIn("no-history@example.com", csrf)), 500);
        } finally {
            testDatabase.reset();
        }
    }

    @Test
    void interestsRespondAccordingToContract() throws Exception {
        // Public: the same documented response without and with a signed-in user.
        assertConformsToContract("/api/v1/interests", get("/api/v1/interests"), 200);
        assertConformsToContract("/api/v1/interests", get("/api/v1/interests").with(user("player")), 200);
    }

    @Test
    void systemStatusRespondsAccordingToContract() throws Exception {
        assertConformsToContract("/api/v1/system/status", get("/api/v1/system/status"), 200);
    }

    @Test
    void csrfTokenRespondsAccordingToContract() throws Exception {
        MockHttpServletResponse response = assertConformsToContract("/api/v1/auth/csrf", get("/api/v1/auth/csrf"), 204);

        assertThat(response.getCookie("XSRF-TOKEN")).isNotNull();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void registrationRespondsAccordingToContract() throws Exception {
        String valid = """
                {"email":"contract@example.com","password":"correct-horse-battery","displayName":"Contract",\
                "adultConfirmed":true}""";
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        try {
            assertConformsToContract(REGISTER, "POST", register(valid, "192.0.2.10").with(csrf), 201);
            assertConformsToContract(REGISTER, "POST", register("""
                    {"email":"not-an-email","password":"short","displayName":"C","adultConfirmed":false}""", "192.0.2.10")
                    .with(csrf), 400);
            assertConformsToContract(REGISTER, "POST", register("{\"email\":", "192.0.2.10").with(csrf), 400);
            assertConformsToContract(REGISTER, "POST", register(valid, "192.0.2.10"), 403);
            assertConformsToContract(REGISTER, "POST", register(valid, "192.0.2.10").with(csrf), 409);
            // 10 attempts per IP and hour: the 4 above (201, 400, 400, 409; the 403 is not counted) and
            // 6 more duplicates, then 429.
            for (int i = 0; i < 6; i++) {
                assertThat(perform(register(valid, "192.0.2.10").with(csrf)).getStatus()).isEqualTo(409);
            }
            MockHttpServletResponse throttled =
                    assertConformsToContract(REGISTER, "POST", register(valid, "192.0.2.10").with(csrf), 429);
            assertThat(throttled.getHeader(HttpHeaders.RETRY_AFTER)).matches("[1-9][0-9]*");
        } finally {
            testDatabase.reset();
        }
    }

    @Test
    void loginAndLogoutRespondAccordingToContract() throws Exception {
        String password = "correct-horse-battery";
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at)
                VALUES ('login@example.com', ?, 'Login', now())""", passwordEncoder.encode(password));
        String valid = "{\"email\":\"login@example.com\",\"password\":\"" + password + "\"}";
        String wrong = "{\"email\":\"login@example.com\",\"password\":\"wrong-password-123\"}";
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        try {
            MockHttpServletResponse signedIn = assertConformsToContract(LOGIN, "POST", jsonPost(LOGIN, valid, "192.0.2.20").with(csrf), 200);
            Cookie session = new Cookie("SESSION", signedIn.getCookie("SESSION").getValue());
            assertConformsToContract(LOGIN, "POST", jsonPost(LOGIN, "{\"email\":\"\",\"password\":\"\"}", "192.0.2.20").with(csrf), 400);
            assertConformsToContract(LOGIN, "POST", jsonPost(LOGIN, "{\"email\":", "192.0.2.20").with(csrf), 400);
            assertConformsToContract(LOGIN, "POST", jsonPost(LOGIN, valid, "192.0.2.20"), 403);
            // 5 failed logins of the account from one IP, then 429.
            for (int i = 0; i < 5; i++) {
                assertConformsToContract(LOGIN, "POST", jsonPost(LOGIN, wrong, "192.0.2.20").with(csrf), 401);
            }
            MockHttpServletResponse throttled = assertConformsToContract(LOGIN, "POST", jsonPost(LOGIN, valid, "192.0.2.20").with(csrf), 429);
            assertThat(throttled.getHeader(HttpHeaders.RETRY_AFTER)).matches("[1-9][0-9]*");

            assertConformsToContract(LOGOUT, "POST", post(LOGOUT).cookie(session), 403);
            assertConformsToContract(LOGOUT, "POST", post(LOGOUT).cookie(session).with(csrf), 204);
        } finally {
            testDatabase.reset();
        }
    }

    @Test
    void actuatorEndpointsRespondAccordingToContract() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/info"}) {
            assertConformsToContract(path, get(path).accept(MediaType.APPLICATION_JSON), 200);
        }
    }

    @Test
    void requestsOutsideTheContractReturnProblemDetails() throws Exception {
        // The errors that info.description documents for every operation.
        RealCsrfToken csrf = RealCsrfToken.fetch(mockMvc);
        Map<ProblemCode, MockHttpServletRequestBuilder> requests = Map.of(
                ProblemCode.AUTHENTICATION_REQUIRED, get("/api/v1/system/unknown"),
                ProblemCode.NOT_FOUND, get("/api/v1/system/unknown").with(user("player")),
                ProblemCode.CSRF_TOKEN_INVALID, post("/api/v1/system/status"),
                ProblemCode.METHOD_NOT_ALLOWED, post("/api/v1/system/status").with(csrf),
                ProblemCode.NOT_ACCEPTABLE, get("/api/v1/system/status").accept(MediaType.APPLICATION_XML));

        for (Map.Entry<ProblemCode, MockHttpServletRequestBuilder> entry : requests.entrySet()) {
            MockHttpServletResponse response = perform(entry.getValue());
            String body = response.getContentAsString(StandardCharsets.UTF_8);

            assertThat(response.getStatus()).as(body).isEqualTo(entry.getKey().status().value());
            assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(Json31.mapper().readTree(body).path("code").asText()).isEqualTo(entry.getKey().name());
            assertThat(contract.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        }
    }

    @Test
    void contractIsServedAndSelectedByDefaultInSwaggerUi() throws Exception {
        // Real HTTP request: media-type mappings are applied by the embedded Tomcat, not by MockMvc.
        HttpResponse<String> yaml = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/openapi.yaml")).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(yaml.statusCode()).isEqualTo(200);
        assertThat(yaml.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/yaml"));
        assertThat(OpenApiContract.fromYaml(yaml.body()).differencesTo(contract, path -> true)).isEmpty();

        JsonNode swaggerConfig = Json31.mapper().readTree(
                perform(get("/v3/api-docs/swagger-config")).getContentAsString(StandardCharsets.UTF_8));
        assertThat(swaggerConfig.path("urls.primaryName").asText()).isEqualTo("Contract (openapi.yaml)");
        Set<String> urls = new TreeSet<>();
        swaggerConfig.path("urls").forEach(entry -> urls.add(entry.path("url").asText()));
        assertThat(urls).containsExactlyInAnyOrder("/openapi.yaml", "/v3/api-docs");
    }

    private MockHttpServletResponse assertConformsToContract(String path, MockHttpServletRequestBuilder request,
                                                             int expectedStatus) throws Exception {
        return assertConformsToContract(path, "GET", request, expectedStatus);
    }

    private MockHttpServletResponse assertConformsToContract(String path, String method, MockHttpServletRequestBuilder request,
                                                             int expectedStatus) throws Exception {
        MockHttpServletResponse response = perform(request);
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as("%s %s status: %s", method, path, body).isEqualTo(expectedStatus);
        assertThat(contract.validateResponse(path, method, response.getStatus(), response.getHeader(HttpHeaders.CONTENT_TYPE), body))
                .as("%s %s %d response vs contract", method, path, expectedStatus)
                .isEmpty();
        return response;
    }

    private static MockHttpServletRequestBuilder profilePatch(String body) {
        return patch(PROFILE).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A registration from {@code clientIp}, so the throttle counter belongs to this test only. */
    private static MockHttpServletRequestBuilder register(String body, String clientIp) {
        return jsonPost(REGISTER, body, clientIp);
    }

    /** A JSON {@code POST} from {@code clientIp}, so the throttle counters belong to this test only. */
    private static MockHttpServletRequestBuilder jsonPost(String path, String body, String clientIp) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                });
    }

    /** Signs in from this test's client address and returns the session cookie. */
    private Cookie signIn(String email, RealCsrfToken csrf) throws Exception {
        MockHttpServletResponse login = perform(jsonPost(LOGIN, "{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}", "192.0.2.50")
                .with(csrf));
        assertThat(login.getStatus()).as(login.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return new Cookie("SESSION", login.getCookie("SESSION").getValue());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
