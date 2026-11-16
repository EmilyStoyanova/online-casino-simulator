package com.casino.wallet.controller;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * {@code GET /api/v1/wallet/transactions} end to end (W3-08, FR-3) with the real filter chain,
 * Spring Session JDBC and PostgreSQL; every response is checked against {@code openapi.yaml}.
 *
 * <p><b>Histories with several entries.</b> The paging tests build long histories in SQL from
 * {@code STARTING_BALANCE} rows ({@link #appendEntries}, keeping balance = sum of entries), which keeps
 * them independent of any game rule. The real Week 4 types, written through {@link WalletService}, are
 * covered by {@link #rouletteStakesAndPayoutsAreShownWithTheirPublicTypes}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WalletTransactionsIT {

    private static final String TRANSACTIONS = "/api/v1/wallet/transactions";
    private static final String PASSWORD = "correct-horse-battery-2b9d";
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
    void aNewPlayersHistoryIsTheirStartingEntry() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);

        MockHttpServletResponse response = expectPage(get(TRANSACTIONS).cookie(signIn("alice@example.com")));

        JsonNode page = json(response);
        assertThat(page.path("items")).hasSize(1);
        JsonNode entry = page.path("items").get(0);
        assertThat(entry.path("id").asLong()).isEqualTo(ids(alice).getFirst());
        assertThat(entry.path("type").asText()).isEqualTo("STARTING_BALANCE");
        assertThat(entry.path("amount").asLong()).isEqualTo(1000);
        assertThat(entry.path("balanceAfter").asLong()).isEqualTo(1000);
        assertThat(Instant.parse(entry.path("createdAt").asText())).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT t.created_at FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ?",
                Timestamp.class, alice).toInstant());
        // AC-3.5: exactly the documented fields; nextCursor is always present, null on the last page.
        assertThat(fieldNames(entry)).containsExactlyInAnyOrder("id", "type", "amount", "balanceAfter", "createdAt");
        assertThat(fieldNames(page)).containsExactlyInAnyOrder("items", "nextCursor");
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(content(response)).doesNotContain(alice.toString(), "starting-balance", "walletId", "idempotency");
    }

    @Test
    void anEmptyHistoryIsAnEmptyLastPage() throws Exception {
        // A wallet without entries does not occur in production; the page is still well-formed.
        UUID bob = accounts.active("bob@example.com", PASSWORD);
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance) VALUES (?, 0)", bob);

        JsonNode page = json(expectPage(get(TRANSACTIONS).cookie(signIn("bob@example.com"))));

        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void pagesFollowTheCursorNewestFirstWithoutRepeatsOrGaps() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        appendEntries(alice, 24);
        Cookie session = signIn("alice@example.com");
        List<Long> all = ids(alice);
        assertThat(all).hasSize(25).isSortedAccordingTo((a, b) -> Long.compare(b, a));

        // Default limit 20: 20 + 5.
        assertThat(pageSizes(session, null)).containsExactly(20, 5);
        assertThat(traverse(session, null)).isEqualTo(all);
        // Custom limits, including both bounds, and a limit equal to the history (no empty last page).
        assertThat(pageSizes(session, 7)).containsExactly(7, 7, 7, 4);
        assertThat(traverse(session, 7)).isEqualTo(all);
        assertThat(pageSizes(session, 1)).hasSize(25).containsOnly(1);
        assertThat(traverse(session, 1)).isEqualTo(all);
        assertThat(pageSizes(session, 100)).containsExactly(25);
        assertThat(pageSizes(session, 25)).containsExactly(25);
        assertThat(pageSizes(session, 24)).containsExactly(24, 1);
    }

    @Test
    void entriesAddedBetweenPagesAppearOnlyOnANewFirstPage() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        appendEntries(alice, 9);
        Cookie session = signIn("alice@example.com");
        List<Long> before = ids(alice);

        JsonNode first = json(expectPage(get(TRANSACTIONS).param("limit", "4").cookie(session)));
        appendEntries(alice, 3);
        List<Long> rest = new ArrayList<>();
        String cursor = first.path("nextCursor").asText();
        while (cursor != null) {
            JsonNode page = json(expectPage(get(TRANSACTIONS).param("limit", "4").param("cursor", cursor).cookie(session)));
            page.path("items").forEach(item -> rest.add(item.path("id").asLong()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        }

        // AC-3.3: the old pages continue exactly where they were; nothing repeated or skipped.
        List<Long> seen = new ArrayList<>(idsOf(first));
        seen.addAll(rest);
        assertThat(seen).isEqualTo(before);
        // The 3 new entries lead a new first page.
        assertThat(idsOf(json(expectPage(get(TRANSACTIONS).param("limit", "3").cookie(session))))).isEqualTo(ids(alice).subList(0, 3))
                .allSatisfy(id -> assertThat(id).isGreaterThan(before.getFirst()));
    }

    @Test
    void everyPlayerSeesOnlyTheirOwnEntriesWhateverTheRequestSays() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        UUID bob = accounts.activePlayerWithWallet("bob@example.com", PASSWORD, walletService);
        // Interleaved ids: the two ledgers share one id sequence.
        for (int i = 0; i < 6; i++) {
            appendEntries(i % 2 == 0 ? alice : bob, 1);
        }
        Cookie aliceSession = signIn("alice@example.com");
        Cookie bobSession = signIn("bob@example.com");
        assertThat(traverse(aliceSession, 2)).isEqualTo(ids(alice));
        assertThat(traverse(bobSession, 2)).isEqualTo(ids(bob));

        // Alice's cursor used by Bob: a position in Bob's own ledger, never Alice's entries (AC-3.4).
        String aliceCursor = json(expectPage(get(TRANSACTIONS).param("limit", "1").cookie(aliceSession))).path("nextCursor").asText();
        JsonNode bobWithAliceCursor = json(expectPage(get(TRANSACTIONS).param("cursor", aliceCursor).cookie(bobSession)));
        assertThat(idsOf(bobWithAliceCursor)).isNotEmpty().isSubsetOf(ids(bob)).doesNotContainAnyElementsOf(ids(alice));
        // Ids of the other player or wallet as parameters are ignored; ids in the path are unknown paths.
        String alicesWallet = jdbcTemplate.queryForObject("SELECT id::text FROM wallets WHERE user_id = ?", String.class, alice);
        assertThat(idsOf(json(expectPage(get(TRANSACTIONS).param("userId", alice.toString()).param("walletId", alicesWallet)
                .cookie(bobSession))))).isEqualTo(ids(bob));
        expectProblem(perform(get(TRANSACTIONS + "/" + alice).cookie(bobSession)), ProblemCode.NOT_FOUND);
        expectProblem(perform(get("/api/v1/wallet/" + alicesWallet + "/transactions").cookie(bobSession)), ProblemCode.NOT_FOUND);
    }

    @Test
    void invalidPageRequestsAreRejectedWithTheDocumentedProblems() throws Exception {
        accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        Cookie session = signIn("alice@example.com");

        for (String limit : new String[] {"0", "101", "-5"}) {
            JsonNode problem = json(expectProblem(perform(get(TRANSACTIONS).param("limit", limit).cookie(session)),
                    ProblemCode.VALIDATION_FAILED));
            assertThat(problem.path("errors").get(0).path("field").asText()).as(limit).isEqualTo("limit");
        }
        // Decimal digits only (W3-09): hexadecimal and signed forms are malformed, 5 is a page size.
        for (String limit : new String[] {"twenty", "0x10", "+5"}) {
            expectProblem(perform(get(TRANSACTIONS).param("limit", limit).cookie(session)), ProblemCode.MALFORMED_REQUEST);
        }
        assertThat(perform(get(TRANSACTIONS).param("limit", "5").cookie(session)).getStatus()).isEqualTo(200);
        for (String cursor : new String[] {"", "not-a-cursor", "djE6MA", "a".repeat(65), "djE6NDI=", "<script>"}) {
            MockHttpServletResponse response = expectProblem(perform(get(TRANSACTIONS).param("cursor", cursor).cookie(session)),
                    ProblemCode.VALIDATION_FAILED);
            assertThat(json(response).path("errors").toString()).as(cursor)
                    .isEqualTo("[{\"field\":\"cursor\",\"message\":\"is not a valid cursor\"}]");
        }
    }

    @Test
    void anonymousUsersEndedSessionsAndAdministratorsAreRejected() throws Exception {
        // AC-3.6 (as AC-2.2–AC-2.4).
        MockHttpServletResponse anonymous = expectProblem(perform(get(TRANSACTIONS)), ProblemCode.AUTHENTICATION_REQUIRED);
        assertThat(anonymous.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(header -> header.startsWith("SESSION="));

        accounts.activeAdministrator("admin@example.com", PASSWORD);
        expectProblem(perform(get(TRANSACTIONS).cookie(signIn("admin@example.com"))), ProblemCode.ACCESS_DENIED);

        accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        Cookie session = signIn("alice@example.com");
        assertThat(perform(post("/api/v1/auth/logout").cookie(session).with(csrf)).getStatus()).isEqualTo(204);
        expectProblem(perform(get(TRANSACTIONS).cookie(session)), ProblemCode.SESSION_EXPIRED);
    }

    @Test
    void readingTheHistoryNeverChangesAWalletOrTheLedger() throws Exception {
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        appendEntries(alice, 5);
        Cookie session = signIn("alice@example.com");
        String before = walletRows();

        traverse(session, 2);
        traverse(session, null);

        assertThat(walletRows()).isEqualTo(before);
    }

    @Test
    void rouletteStakesAndPayoutsAreShownWithTheirPublicTypes() throws Exception {
        // W4-03: the real ledger types of Week 4, written through WalletService as a round will (W4-06).
        // expectPage validates every response against the contract, whose type enum has exactly the
        // production values.
        UUID alice = accounts.activePlayerWithWallet("alice@example.com", PASSWORD, walletService);
        walletService.debit(alice, new WalletOperation(WalletTransactionType.ROULETTE_STAKE, 30, "roulette-round:r1:stake", "r1"));
        walletService.credit(alice, new WalletOperation(WalletTransactionType.ROULETTE_PAYOUT, 360, "roulette-round:r1:payout", "r1"));
        walletService.debit(alice, new WalletOperation(WalletTransactionType.ROULETTE_STAKE, 50, "roulette-round:r2:stake", "r2"));
        Cookie session = signIn("alice@example.com");

        MockHttpServletResponse response = expectPage(get(TRANSACTIONS).cookie(session));

        assertThat(json(response).path("items"))
                .extracting(item -> item.path("type").asText() + " " + item.path("amount").asLong() + " -> "
                        + item.path("balanceAfter").asLong())
                .containsExactly("ROULETTE_STAKE -50 -> 1280", "ROULETTE_PAYOUT 360 -> 1330", "ROULETTE_STAKE -30 -> 970",
                        "STARTING_BALANCE 1000 -> 1000");
        // Idempotency keys and references (round ids) stay internal.
        assertThat(content(response)).doesNotContain("roulette-round", "\"r1\"", "\"r2\"");
        assertThat(json(perform(get("/api/v1/wallet").cookie(session))).path("balance").asLong()).isEqualTo(1280);
    }

    @Test
    void aPlayerWithoutAWalletIsAnInternalError() throws Exception {
        UUID bob = accounts.active("bob@example.com", PASSWORD);

        MockHttpServletResponse response = expectProblem(perform(get(TRANSACTIONS).cookie(signIn("bob@example.com"))),
                ProblemCode.INTERNAL_ERROR);

        assertThat(content(response)).doesNotContain(bob.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallets", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Appends {@code count} {@code STARTING_BALANCE} entries of 1 coin and raises the balance to match,
     * as one consistent change (see the class documentation).
     */
    private void appendEntries(UUID user, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    WITH w AS (UPDATE wallets SET balance = balance + 1 WHERE user_id = ? RETURNING id, balance)
                    INSERT INTO wallet_transactions (wallet_id, type, amount, balance_after, idempotency_key)
                    SELECT id, 'STARTING_BALANCE', 1, balance, 'fixture-' || gen_random_uuid() FROM w""", user);
        }
    }

    /** The user's entry ids, newest first, read directly. */
    private List<Long> ids(UUID user) {
        return jdbcTemplate.queryForList("""
                SELECT t.id FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ? ORDER BY t.id DESC""",
                Long.class, user);
    }

    /** All entry ids seen when following {@code nextCursor} from the first page. */
    private List<Long> traverse(Cookie session, Integer limit) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode page : pages(session, limit)) {
            ids.addAll(idsOf(page));
        }
        return ids;
    }

    private List<Integer> pageSizes(Cookie session, Integer limit) throws Exception {
        return pages(session, limit).stream().map(page -> page.path("items").size()).toList();
    }

    private List<JsonNode> pages(Cookie session, Integer limit) throws Exception {
        List<JsonNode> pages = new ArrayList<>();
        String cursor = null;
        do {
            MockHttpServletRequestBuilder request = get(TRANSACTIONS).cookie(session);
            if (limit != null) {
                request.param("limit", limit.toString());
            }
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = json(expectPage(request));
            pages.add(page);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            assertThat(pages).as("pages").hasSizeLessThanOrEqualTo(200);
        } while (cursor != null);
        return pages;
    }

    private static List<Long> idsOf(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Cookie signIn(String email) throws Exception {
        MockHttpServletResponse login = perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .with(csrf)
                .with(request -> {
                    request.setRemoteAddr("192.0.2.90");
                    return request;
                }));
        assertThat(login.getStatus()).as(content(login)).isEqualTo(200);
        return cookie(sessionCookie(login));
    }

    /** {@code 200}, {@code no-store}, conforming to the contract. */
    private MockHttpServletResponse expectPage(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).as(content(response)).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertContract(response);
        return response;
    }

    private static void assertContract(MockHttpServletResponse response) throws Exception {
        assertThat(CONTRACT.validateResponse(TRANSACTIONS, "GET", response.getStatus(), response.getContentType(), content(response)))
                .as("GET %d vs openapi.yaml", response.getStatus()).isEmpty();
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

    /** Status, code, Problem Details per contract (for this operation where documented), {@code no-store}. */
    private static MockHttpServletResponse expectProblem(MockHttpServletResponse response, ProblemCode code) throws Exception {
        String body = content(response);
        assertThat(response.getStatus()).as(body).isEqualTo(code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        assertThat(body).doesNotContain("Exception", "org.springframework", "com.casino", "SQL");
        if (code != ProblemCode.NOT_FOUND) {
            assertContract(response);
        }
        return response;
    }

    private static String content(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(content(response));
    }
}
