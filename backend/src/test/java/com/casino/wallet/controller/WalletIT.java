package com.casino.wallet.controller;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * {@code GET /api/v1/wallet} end to end (W3-07, FR-2) with the real filter chain, Spring Session JDBC
 * and PostgreSQL: sessions from real logins, balances changed through {@link WalletService} (roulette
 * stake and payout types), every response checked against {@code openapi.yaml}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WalletIT {

    private static final String WALLET = "/api/v1/wallet";
    private static final String PASSWORD = "correct-horse-battery-8c1e";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();

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

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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
    void aSignedInPlayerGetsTheStoredBalanceAndChangeTime() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);

        MockHttpServletResponse response = expectWallet(get(WALLET).cookie(signIn("alice@example.com")));

        // AC-2.1: exactly the stored values, and nothing else (no ids, no owner).
        JsonNode wallet = json(response);
        assertThat(wallet.path("balance").isIntegralNumber()).isTrue();
        assertThat(wallet.path("balance").asLong()).isEqualTo(1000);
        assertThat(Instant.parse(wallet.path("updatedAt").asText())).isEqualTo(storedUpdatedAt(alice));
        List<String> fields = new ArrayList<>();
        wallet.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("balance", "updatedAt");
        assertThat(content(response)).doesNotContain(alice.toString());
        // AC-2.4
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void everyPlayerSeesOnlyTheirOwnWalletWhateverTheRequestSays() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        UUID bob = accounts.activePlayerWithWallet("bob@example.com", PASSWORD, walletService);
        Cookie aliceSession = signIn("alice@example.com");
        Cookie bobSession = signIn("bob@example.com");

        // AC-2.5: interleaved changes; each read returns its own session's balance.
        walletService.debit(alice, new WalletOperation(WalletTransactionType.ROULETTE_STAKE, 300, "spend-1", null));
        assertThat(balance(aliceSession)).isEqualTo(700);
        assertThat(balance(bobSession)).isEqualTo(1000);
        walletService.credit(bob, new WalletOperation(WalletTransactionType.ROULETTE_PAYOUT, 50, "gift-1", null));
        assertThat(balance(bobSession)).isEqualTo(1050);
        assertThat(balance(aliceSession)).isEqualTo(700);

        // No request value selects a wallet: parameters are ignored, and an id in the path is an unknown path.
        String bobsWallet = jdbcTemplate.queryForObject("SELECT id::text FROM wallets WHERE user_id = ?", String.class, bob);
        MockHttpServletResponse withParameters = expectWallet(get(WALLET).param("userId", bob.toString())
                .param("walletId", bobsWallet).cookie(aliceSession));
        assertThat(json(withParameters).path("balance").asLong()).isEqualTo(700);
        expectProblem(perform(get(WALLET + "/" + bob).cookie(aliceSession)), ProblemCode.NOT_FOUND);
        expectProblem(perform(get(WALLET + "/" + bobsWallet).cookie(aliceSession)), ProblemCode.NOT_FOUND);
    }

    @Test
    void anonymousRequestsAndEndedSessionsAreUnauthenticated() throws Exception {
        // AC-2.2
        MockHttpServletResponse anonymous = expectProblem(perform(get(WALLET)), ProblemCode.AUTHENTICATION_REQUIRED);
        assertThat(anonymous.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(header -> header.startsWith("SESSION="));
        assertContract(anonymous, 401);

        accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        Cookie session = signIn("alice@example.com");
        assertThat(perform(post("/api/v1/auth/logout").cookie(session).with(csrf)).getStatus()).isEqualTo(204);
        assertContract(expectProblem(perform(get(WALLET).cookie(session)), ProblemCode.SESSION_EXPIRED), 401);
    }

    @Test
    void administratorsAreForbiddenAndGetNoWallet() throws Exception {
        accounts.activeAdministrator("admin@example.com", PASSWORD);
        Cookie admin = signIn("admin@example.com");

        // AC-2.3 (P-W3-3): the role rule, also for any other path under /api/v1/wallet.
        assertContract(expectProblem(perform(get(WALLET).cookie(admin)), ProblemCode.ACCESS_DENIED), 403);
        expectProblem(perform(get(WALLET + "/unknown").cookie(admin)), ProblemCode.ACCESS_DENIED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallets", Integer.class)).isZero();
        // The administrator's session itself stays valid for the operations it may use.
        assertThat(perform(get("/api/v1/users/me").cookie(admin)).getStatus()).isEqualTo(200);
    }

    @Test
    void readingNeverCreatesOrChangesAWallet() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        Cookie session = signIn("alice@example.com");
        String before = walletRows();

        for (int i = 0; i < 3; i++) {
            expectWallet(get(WALLET).cookie(session));
        }

        // Balance, updated_at and the ledger are exactly as before.
        assertThat(walletRows()).isEqualTo(before);
        assertThat(storedUpdatedAt(alice)).isEqualTo(Instant.parse(json(expectWallet(get(WALLET).cookie(session))).path("updatedAt").asText()));
    }

    @Test
    void aPlayerWithoutAWalletIsAnInternalErrorAndGetsNone() throws Exception {
        // Inserted directly, bypassing registration: a data inconsistency (AC-2.6).
        UUID bob = accounts.active("bob@example.com", PASSWORD);
        Cookie session = signIn("bob@example.com");

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = expectProblem(perform(get(WALLET).cookie(session)), ProblemCode.INTERNAL_ERROR);
            assertContract(response, 500);
            assertThat(content(response)).doesNotContain(bob.toString(), "No wallet", "WalletNotFound");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) || ':' || (SELECT count(*) FROM wallet_transactions) FROM wallets",
                String.class)).isEqualTo("0:0");
        // The session is not affected by the error.
        assertThat(perform(get("/api/v1/users/me").cookie(session)).getStatus()).isEqualTo(200);
    }

    @Test
    void theSessionStaysValidAcrossPageReloads() throws Exception {
        accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        Cookie session = signIn("alice@example.com");

        // Each reload of the SPA sends only the cookie; the session is read from the database each time,
        // and no new session is issued (restarts: SessionRestartIT).
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse reload = expectWallet(get(WALLET).cookie(session));
            assertThat(reload.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(header -> header.startsWith("SESSION="));
        }
    }

    @Test
    void theWalletModuleServesOnlyItsTwoReadOperations() {
        // W3-09 (P-W3-9): every handler of the wallet module, and every handler under /api/v1/wallet, is GET.
        Map<String, Set<RequestMethod>> handlers = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> info.getPatternValues().stream()
                .filter(path -> path.startsWith(WALLET) || method.getBeanType().getPackageName().startsWith("com.casino.wallet"))
                .forEach(path -> handlers.put(path, info.getMethodsCondition().getMethods())));

        assertThat(handlers).containsOnly(entry(WALLET, Set.of(RequestMethod.GET)), entry(WALLET + "/transactions", Set.of(RequestMethod.GET)));
    }

    @Test
    void thereIsNoOperationThatChangesAWallet() throws Exception {
        accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        Cookie session = signIn("alice@example.com");
        String before = walletRows();

        // P-W3-9: no public credit or debit. With a valid CSRF token the method is unknown; without one,
        // CSRF protection answers first, as for every unsafe method.
        for (MockHttpServletRequestBuilder change : List.of(post(WALLET), put(WALLET), delete(WALLET))) {
            expectProblem(perform(change.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000000}").cookie(session).with(csrf)),
                    ProblemCode.METHOD_NOT_ALLOWED);
        }
        expectProblem(perform(post(WALLET).contentType(MediaType.APPLICATION_JSON).content("{}").cookie(session)),
                ProblemCode.CSRF_TOKEN_INVALID);
        assertThat(walletRows()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ helpers

    private Cookie signIn(String email) throws Exception {
        MockHttpServletResponse login = perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .with(csrf)
                .with(request -> {
                    request.setRemoteAddr("192.0.2.80");
                    return request;
                }));
        assertThat(login.getStatus()).as(content(login)).isEqualTo(200);
        return cookie(sessionCookie(login));
    }

    private long balance(Cookie session) throws Exception {
        return json(expectWallet(get(WALLET).cookie(session))).path("balance").asLong();
    }

    /** {@code 200} conforming to the contract. */
    private MockHttpServletResponse expectWallet(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).as(content(response)).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertContract(response, 200);
        return response;
    }

    private static void assertContract(MockHttpServletResponse response, int status) throws Exception {
        assertThat(CONTRACT.validateResponse(WALLET, "GET", status, response.getContentType(), content(response)))
                .as("GET %d vs openapi.yaml", status).isEmpty();
    }

    private Instant storedUpdatedAt(UUID user) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM wallets WHERE user_id = ?", Timestamp.class, user).toInstant();
    }

    /** Every column of every wallet and ledger entry. */
    private String walletRows() {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce((SELECT string_agg(w::text, '|' ORDER BY w.id) FROM wallets w), '') || ' / '
                    || coalesce((SELECT string_agg(t::text, '|' ORDER BY t.id) FROM wallet_transactions t), '')""", String.class);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletResponse expectProblem(MockHttpServletResponse response, ProblemCode code) throws Exception {
        String body = content(response);
        assertThat(response.getStatus()).as(body).isEqualTo(code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        assertThat(body).doesNotContain("Exception", "org.springframework", "com.casino", "SQL");
        return response;
    }

    private static String content(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(content(response));
    }
}
