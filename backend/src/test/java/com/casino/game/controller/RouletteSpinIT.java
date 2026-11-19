package com.casino.game.controller;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
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
import com.casino.game.service.RouletteWheel;
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
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * {@code POST /api/v1/games/roulette/spins} end to end (W4-07, AC-W4-07.1–07.7): real sign-ins, Spring Session
 * JDBC, the security filter chain with CSRF, JSON binding, the round service and PostgreSQL, with a
 * deterministic wheel. Every response is validated against {@code openapi.yaml}; the wallet and its history
 * are read back through the API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RouletteSpinIT {

    private static final String SPINS = "/api/v1/games/roulette/spins";
    private static final String PASSWORD = "correct-horse-battery-5d2a";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    private static final String STRAIGHT_17 = "{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":10}";
    private static final String RED_20 = "{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":20}";

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

    @MockitoBean
    private RouletteWheel wheel;

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
    void aPlayerSpinsWithEveryCategoryAndTheWalletAndHistoryFollow() throws Exception {
        accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        Cookie session = signIn("alice@example.com");
        when(wheel.spin()).thenReturn(17);

        JsonNode round = json(expect(spin(session, UUID.randomUUID().toString(), STRAIGHT_17, RED_20,
                "{\"category\":\"EVEN_ODD\",\"parity\":\"ODD\",\"amount\":5}", "{\"category\":\"LOW_HIGH\",\"range\":\"HIGH\",\"amount\":5}",
                "{\"category\":\"DOZEN\",\"dozen\":\"SECOND\",\"amount\":15}"), 200));

        assertThat(round.path("winningNumber").asInt()).isEqualTo(17);
        assertThat(round.path("winningColor").asText()).isEqualTo("BLACK");
        assertThat(betSummaries(round)).containsExactly("STRAIGHT number=17 10 -> 360", "RED_BLACK color=RED 20 -> 0",
                "EVEN_ODD parity=ODD 5 -> 10", "LOW_HIGH range=HIGH 5 -> 0", "DOZEN dozen=SECOND 15 -> 45");
        assertThat(round.path("totalStake").asLong()).isEqualTo(55);
        assertThat(round.path("totalPayout").asLong()).isEqualTo(415);
        assertThat(round.path("balance").asLong()).isEqualTo(1000 - 55 + 415);
        // The wallet and its history, through the API.
        assertThat(balance(session)).isEqualTo(1360);
        String id = round.path("roundId").asText();
        assertThat(history(session)).containsExactly("ROULETTE_PAYOUT 415 -> 1360", "ROULETTE_STAKE -55 -> 945", "STARTING_BALANCE 1000 -> 1000");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transactions WHERE reference = ?", Long.class, id)).isEqualTo(2);
    }

    @Test
    void aRepeatedRequestReturnsTheSameBodyAndChangesNothing() throws Exception {
        accounts.activePlayerWithWallet("bob@example.com", PASSWORD, walletService);
        Cookie session = signIn("bob@example.com");
        String key = "3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b";
        when(wheel.spin()).thenReturn(17);
        String original = content(expect(spin(session, key, STRAIGHT_17, RED_20), 200));
        String rows = walletRows();

        String replay = content(expect(spin(session, key, STRAIGHT_17, RED_20), 200));
        String reordered = content(expect(spin(session, key, RED_20, STRAIGHT_17), 200));
        // Q18: another spelling of the same UUID is the same key.
        String upperCase = content(expect(spin(session, key.toUpperCase(), STRAIGHT_17, RED_20), 200));

        assertThat(replay).isEqualTo(original);
        assertThat(reordered).isEqualTo(original);
        assertThat(upperCase).isEqualTo(original);
        verify(wheel, times(1)).spin();
        assertThat(walletRows()).isEqualTo(rows);
        assertThat(rounds()).isOne();
    }

    @Test
    void theSameKeyWithOtherBetsIsAConflictAndAnotherPlayerMayUseIt() throws Exception {
        accounts.activePlayerWithWallet("carol@example.com", PASSWORD, walletService);
        accounts.activePlayerWithWallet("dave@example.com", PASSWORD, walletService);
        Cookie carol = signIn("carol@example.com");
        Cookie dave = signIn("dave@example.com");
        String key = UUID.randomUUID().toString();
        when(wheel.spin()).thenReturn(17, 4);
        expect(spin(carol, key, STRAIGHT_17), 200);
        String rows = walletRows();

        expectProblem(spin(carol, key, "{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":11}"), ProblemCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(walletRows()).isEqualTo(rows);

        JsonNode daves = json(expect(spin(dave, key, STRAIGHT_17), 200));
        assertThat(daves.path("balance").asLong()).isEqualTo(990);
        assertThat(balance(carol)).isEqualTo(1350);
        assertThat(rounds()).isEqualTo(2);
    }

    @Test
    void insufficientFundsIsAConflictBeforeAnyDraw() throws Exception {
        accounts.activePlayerWithWallet("erin@example.com", PASSWORD, walletService);
        Cookie session = signIn("erin@example.com");
        String rows = walletRows();
        List<String> tenBets = new ArrayList<>();
        for (int n = 1; n <= 10; n++) {
            tenBets.add("{\"category\":\"STRAIGHT\",\"number\":" + n + ",\"amount\":1000}");
        }

        expectProblem(spin(session, UUID.randomUUID().toString(), tenBets.toArray(String[]::new)), ProblemCode.INSUFFICIENT_FUNDS);

        verify(wheel, never()).spin();
        assertThat(walletRows()).isEqualTo(rows);
        assertThat(rounds()).isZero();
    }

    @Test
    void rejectedRequestsChangeNothing() throws Exception {
        accounts.activePlayerWithWallet("frank@example.com", PASSWORD, walletService);
        Cookie session = signIn("frank@example.com");
        String rows = walletRows();
        String key = UUID.randomUUID().toString();

        expectProblem(spin(session, "1-1-1-1-1", STRAIGHT_17), ProblemCode.MALFORMED_REQUEST);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(bets(STRAIGHT_17)).cookie(session).with(csrf),
                ProblemCode.MALFORMED_REQUEST);
        for (String amount : new String[] {"10.5", "10.0", "1e1", "\"10\"", "null"}) {
            expectProblem(spin(session, key, "{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":" + amount + "}"), ProblemCode.MALFORMED_REQUEST);
        }
        expectProblem(spin(session, key, "{\"category\":\"COLUMN\",\"amount\":10}"), ProblemCode.MALFORMED_REQUEST);
        expectProblem(spin(session, key, "{\"category\":\"STRAIGHT\",\"number\":37,\"amount\":10}"), ProblemCode.VALIDATION_FAILED);
        expectProblem(spin(session, key, "{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":1001}"), ProblemCode.VALIDATION_FAILED);
        expectProblem(spin(session, key, "{\"category\":\"RED_BLACK\",\"amount\":10}"), ProblemCode.VALIDATION_FAILED);
        expectProblem(spin(session, key, RED_20, "{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":5}"), ProblemCode.VALIDATION_FAILED);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content("{\"bets\":[]}").header("Idempotency-Key", key)
                .cookie(session).with(csrf), ProblemCode.VALIDATION_FAILED);

        verify(wheel, never()).spin();
        assertThat(walletRows()).isEqualTo(rows);
        assertThat(rounds()).isZero();
        // Nothing was recorded for the key: it plays a round now.
        when(wheel.spin()).thenReturn(17);
        expect(spin(session, key, STRAIGHT_17), 200);
    }

    @Test
    void clientValuesForTheResultThePayoutOrThePlayerAreIgnored() throws Exception {
        accounts.activePlayerWithWallet("grace@example.com", PASSWORD, walletService);
        UUID heidi = accounts.activePlayerWithWallet("heidi@example.com", PASSWORD, walletService);
        Cookie grace = signIn("grace@example.com");
        when(wheel.spin()).thenReturn(5);

        JsonNode round = json(expect(post(SPINS).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                        {"bets":[{"category":"STRAIGHT","number":17,"amount":10,"payout":1000000}],
                         "winningNumber":17,"payout":1000000,"balance":9007199254740991,"userId":"%s","seed":17}""".formatted(heidi))
                .cookie(grace).with(csrf), 200));

        assertThat(round.path("winningNumber").asInt()).isEqualTo(5);
        assertThat(round.path("totalPayout").asLong()).isZero();
        assertThat(round.path("balance").asLong()).isEqualTo(990);
        assertThat(balance(grace)).isEqualTo(990);
        // The other player's wallet is untouched.
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", Long.class, heidi)).isEqualTo(1000);
    }

    @Test
    void onlyASignedInPlayerWithACsrfTokenMaySpin() throws Exception {
        accounts.activePlayerWithWallet("ivan@example.com", PASSWORD, walletService);
        accounts.activeAdministrator("admin@example.com", PASSWORD);
        Cookie player = signIn("ivan@example.com");
        String key = UUID.randomUUID().toString();

        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(bets(STRAIGHT_17)).header("Idempotency-Key", key).with(csrf),
                ProblemCode.AUTHENTICATION_REQUIRED);
        expectProblem(spin(signIn("admin@example.com"), key, STRAIGHT_17), ProblemCode.ACCESS_DENIED);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(bets(STRAIGHT_17)).header("Idempotency-Key", key)
                .cookie(player), ProblemCode.CSRF_TOKEN_INVALID);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(bets(STRAIGHT_17)).header("Idempotency-Key", key)
                .header("X-XSRF-TOKEN", "forged").cookie(player).cookie(new Cookie("XSRF-TOKEN", "other")), ProblemCode.CSRF_TOKEN_INVALID);
        // An ended session.
        assertThat(mockMvc.perform(post("/api/v1/auth/logout").cookie(player).with(csrf)).andReturn().getResponse().getStatus()).isEqualTo(204);
        expectProblem(spin(player, key, STRAIGHT_17), ProblemCode.SESSION_EXPIRED);

        verify(wheel, never()).spin();
        assertThat(rounds()).isZero();
    }

    @Test
    void theSpinIsTheOnlyGameOperationAndChangesNoOtherPath() {
        // AC-W4-07.6: one state-changing game operation; no public wallet change.
        Map<String, Set<RequestMethod>> handlers = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> info.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/v1/games") || path.startsWith("/api/v1/wallet")
                        || method.getBeanType().getPackageName().startsWith("com.casino.game"))
                .forEach(path -> handlers.put(path, info.getMethodsCondition().getMethods())));

        assertThat(handlers).containsOnly(entry(SPINS, Set.of(RequestMethod.POST)), entry("/api/v1/wallet", Set.of(RequestMethod.GET)),
                entry("/api/v1/wallet/transactions", Set.of(RequestMethod.GET)));
    }

    @Test
    void theIdempotencyKeyIsTheOnlyUuidBoundFromTheRequest() {
        // Q18 scope: CanonicalUuidParameters applies to every UUID that Spring MVC binds from the request (headers,
        // query, path, cookies; also unannotated simple parameters). Today that is this header only, so no other
        // endpoint's behaviour changed; a new UUID parameter shows up here and must be reviewed. JSON bodies are
        // read by Jackson, not by the binder, and no handler uses form or model-attribute binding.
        List<String> uuidParameters = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            for (MethodParameter parameter : method.getMethodParameters()) {
                if (parameter.getParameterType() == UUID.class) {
                    uuidParameters.add(ClassUtils.getUserClass(method.getBeanType()).getSimpleName() + "." + method.getMethod().getName()
                            + "(" + java.util.Arrays.stream(parameter.getParameterAnnotations()).map(annotation -> annotation.annotationType())
                            .filter(type -> type.getPackageName().equals("org.springframework.web.bind.annotation"))
                            .map(Class::getSimpleName).findFirst().orElse("unannotated") + ")");
                }
            }
        });

        assertThat(uuidParameters).containsExactly("RouletteController.spin(RequestHeader)");
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder spin(Cookie session, String key, String... bets) {
        return post(SPINS).contentType(MediaType.APPLICATION_JSON).content(bets(bets)).header("Idempotency-Key", key)
                .cookie(session).with(csrf);
    }

    private static String bets(String... bets) {
        return "{\"bets\":[" + String.join(",", bets) + "]}";
    }

    private Cookie signIn(String email) throws Exception {
        MockHttpServletResponse login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .with(csrf)
                .with(request -> {
                    request.setRemoteAddr("192.0.2.90");
                    return request;
                })).andReturn().getResponse();
        assertThat(login.getStatus()).as(content(login)).isEqualTo(200);
        return cookie(sessionCookie(login));
    }

    private long balance(Cookie session) throws Exception {
        return json(mockMvc.perform(get("/api/v1/wallet").cookie(session)).andReturn().getResponse()).path("balance").asLong();
    }

    private List<String> history(Cookie session) throws Exception {
        List<String> entries = new ArrayList<>();
        json(mockMvc.perform(get("/api/v1/wallet/transactions").cookie(session)).andReturn().getResponse()).path("items")
                .forEach(item -> entries.add(item.path("type").asText() + " " + item.path("amount").asLong() + " -> "
                        + item.path("balanceAfter").asLong()));
        return entries;
    }

    private static List<String> betSummaries(JsonNode round) {
        List<String> summaries = new ArrayList<>();
        round.path("bets").forEach(bet -> {
            StringBuilder option = new StringBuilder();
            bet.properties().forEach(field -> {
                if (!List.of("category", "amount", "payout").contains(field.getKey())) {
                    option.append(field.getKey()).append('=').append(field.getValue().asText());
                }
            });
            summaries.add(bet.path("category").asText() + " " + option + " " + bet.path("amount").asLong() + " -> "
                    + bet.path("payout").asLong());
        });
        return summaries;
    }

    private String walletRows() {
        return jdbcTemplate.queryForObject("SELECT coalesce(string_agg(t::text, '|' ORDER BY t.id), '') FROM wallet_transactions t",
                String.class) + jdbcTemplate.queryForObject("SELECT string_agg(w::text, '|' ORDER BY w.id) FROM wallets w", String.class);
    }

    private long rounds() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM roulette_rounds", Long.class);
    }

    private MockHttpServletResponse expect(MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        String body = content(response);
        assertThat(response.getStatus()).as(body).isEqualTo(status);
        assertThat(CONTRACT.validateResponse(SPINS, "POST", status, response.getContentType(), body))
                .as("POST %s %d vs openapi.yaml", SPINS, status).isEmpty();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        return response;
    }

    private MockHttpServletResponse expectProblem(MockHttpServletRequestBuilder request, ProblemCode code) throws Exception {
        MockHttpServletResponse response = expect(request, code.status().value());
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        return response;
    }

    private static String content(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(content(response));
    }
}
