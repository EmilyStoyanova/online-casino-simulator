package com.casino.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.casino.auth.config.SecurityConfig;
import com.casino.common.config.WebConfig;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import com.casino.game.model.PlayedRound;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteNumberColor;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteRange;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.RouletteWager;
import com.casino.game.model.SettledWager;
import com.casino.game.service.RouletteService;
import com.casino.wallet.exception.BalanceLimitExceededException;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
import com.casino.wallet.exception.InsufficientFundsException;
import com.casino.wallet.exception.WalletBusyException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /api/v1/games/roulette/spins} through the real security filter chain and JSON binding with a
 * mocked {@link RouletteService} (W4-07): the role rule and CSRF, the canonical {@code Idempotency-Key}
 * (Q18), strict JSON integers (Q10), the bet validation and its field paths, the mapping of service errors
 * and the response shape. Every response is validated against {@code openapi.yaml}.
 */
@WebMvcTest(RouletteController.class)
@Import({SecurityConfig.class, WebConfig.class})
class RouletteControllerTest {

    private static final String SPINS = "/api/v1/games/roulette/spins";
    private static final UUID PLAYER = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");
    private static final String KEY = "3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b";
    private static final UUID ROUND = UUID.fromString("6f1c2a0e-8b3d-4c55-9a7e-2f0d5b1e9c41");
    private static final Instant AT = Instant.parse("2026-11-20T10:15:30.123456Z");
    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    private static final String RED_20 = "{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":20}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouletteService rouletteService;

    // ------------------------------------------------------------------ the happy path

    @Test
    void aPlayersSpinIsPlayedAndAnsweredWithTheStoredRound() throws Exception {
        when(rouletteService.spin(eq(PLAYER), eq(UUID.fromString(KEY)), any())).thenReturn(new PlayedRound(ROUND, 17,
                RouletteNumberColor.BLACK, List.of(new SettledWager(new Straight(17), 10, 360),
                new SettledWager(new RedBlack(RouletteBetColor.RED), 20, 0)), 30, 360, 1330, AT));

        MockHttpServletResponse response = expect(spin(bets("{\"category\":\"STRAIGHT\",\"number\":17,\"amount\":10}", RED_20)), 200);

        // Exactly the contract's example: the other option properties are omitted, not null.
        assertThat(body(response)).isEqualTo("""
                {"roundId":"6f1c2a0e-8b3d-4c55-9a7e-2f0d5b1e9c41","winningNumber":17,"winningColor":"BLACK",\
                "bets":[{"category":"STRAIGHT","number":17,"amount":10,"payout":360},\
                {"category":"RED_BLACK","color":"RED","amount":20,"payout":0}],\
                "totalStake":30,"totalPayout":360,"balance":1330,"createdAt":"2026-11-20T10:15:30.123456Z"}""");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(wagers()).containsExactly(new RouletteWager(new Straight(17), 10), new RouletteWager(new RedBlack(RouletteBetColor.RED), 20));
    }

    @Test
    void everyCategoryAndOptionIsBoundToItsSelection() throws Exception {
        stubAnyRound();

        expect(spin(bets("{\"category\":\"STRAIGHT\",\"number\":0,\"amount\":1}", "{\"category\":\"RED_BLACK\",\"color\":\"BLACK\",\"amount\":2}",
                "{\"category\":\"EVEN_ODD\",\"parity\":\"EVEN\",\"amount\":3}", "{\"category\":\"EVEN_ODD\",\"parity\":\"ODD\",\"amount\":4}",
                "{\"category\":\"LOW_HIGH\",\"range\":\"LOW\",\"amount\":5}", "{\"category\":\"LOW_HIGH\",\"range\":\"HIGH\",\"amount\":6}",
                "{\"category\":\"DOZEN\",\"dozen\":\"FIRST\",\"amount\":7}", "{\"category\":\"DOZEN\",\"dozen\":\"SECOND\",\"amount\":8}",
                "{\"category\":\"DOZEN\",\"dozen\":\"THIRD\",\"amount\":9}", "{\"category\":\"STRAIGHT\",\"number\":36,\"amount\":1000}")), 200);

        assertThat(wagers()).containsExactly(new RouletteWager(new Straight(0), 1), new RouletteWager(new RedBlack(RouletteBetColor.BLACK), 2),
                new RouletteWager(new EvenOdd(RouletteParity.EVEN), 3), new RouletteWager(new EvenOdd(RouletteParity.ODD), 4),
                new RouletteWager(new LowHigh(RouletteRange.LOW), 5), new RouletteWager(new LowHigh(RouletteRange.HIGH), 6),
                new RouletteWager(new Dozen(RouletteDozen.FIRST), 7), new RouletteWager(new Dozen(RouletteDozen.SECOND), 8),
                new RouletteWager(new Dozen(RouletteDozen.THIRD), 9), new RouletteWager(new Straight(36), 1000));
    }

    @Test
    void propertiesOutsideTheContractAreIgnoredAndNullOptionsCountAsOmitted() throws Exception {
        stubAnyRound();

        expect(spin("""
                {"bets":[{"category":"STRAIGHT","number":17,"amount":10,"payout":1000000,"color":null,"dozen":null}],
                 "winningNumber":17,"payout":1000000,"balance":9007199254740991,"userId":"00000000-0000-0000-0000-000000000001",
                 "seed":42,"odds":100}"""), 200);

        assertThat(wagers()).containsExactly(new RouletteWager(new Straight(17), 10));
        verify(rouletteService).spin(eq(PLAYER), eq(UUID.fromString(KEY)), any());
    }

    // ------------------------------------------------------------------ Idempotency-Key (Q18)

    @Test
    void theIdempotencyKeyMustBeACanonicalUuid() throws Exception {
        for (String key : new String[] {"1-1-1-1-1", "3f2b8c4e9d1a4e7b8c2f5a6d7e8f9a0b", "3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0", "{" + KEY + "}",
                " " + KEY, KEY + " ", "3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0bb", "zf2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b", "not-a-key", ""}) {
            expectProblem(player(post(SPINS)).contentType(MediaType.APPLICATION_JSON).content(bets(RED_20)).header("Idempotency-Key", key),
                    ProblemCode.MALFORMED_REQUEST);
        }
        expectProblem(player(post(SPINS)).contentType(MediaType.APPLICATION_JSON).content(bets(RED_20)), ProblemCode.MALFORMED_REQUEST);
        verifyNoInteractions(rouletteService);
    }

    @Test
    void upperAndLowerCaseSpellingsAreTheSameKey() throws Exception {
        stubAnyRound();

        expect(player(post(SPINS)).contentType(MediaType.APPLICATION_JSON).content(bets(RED_20)).header("Idempotency-Key", KEY.toUpperCase()), 200);

        verify(rouletteService).spin(eq(PLAYER), eq(UUID.fromString(KEY)), any());
    }

    // ------------------------------------------------------------------ strict JSON integers (Q10) and unreadable values

    @Test
    void amountsAndNumbersMustBeJsonIntegerTokens() throws Exception {
        for (String amount : new String[] {"10.5", "10.0", "1e1", "1E1", "\"10\"", "true", "[10]", "{\"v\":10}", "null",
                "99999999999999999999"}) {
            expectProblem(spin(bets("{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":" + amount + "}")), ProblemCode.MALFORMED_REQUEST);
        }
        for (String number : new String[] {"17.5", "17.0", "1.7e1", "\"17\"", "false", "[17]", "3000000000"}) {
            expectProblem(spin(bets("{\"category\":\"STRAIGHT\",\"number\":" + number + ",\"amount\":10}")), ProblemCode.MALFORMED_REQUEST);
        }
        verifyNoInteractions(rouletteService);
    }

    @Test
    void unknownCategoriesOptionsAndMalformedBodiesAreMalformedRequests() throws Exception {
        for (String bet : new String[] {"{\"category\":\"COLUMN\",\"amount\":10}", "{\"category\":\"red_black\",\"color\":\"RED\",\"amount\":10}",
                "{\"category\":0,\"number\":1,\"amount\":10}", "{\"category\":\"RED_BLACK\",\"color\":\"GREEN\",\"amount\":10}",
                "{\"category\":\"RED_BLACK\",\"color\":1,\"amount\":10}", "{\"category\":\"DOZEN\",\"dozen\":\"FOURTH\",\"amount\":10}",
                "{\"category\":\"LOW_HIGH\",\"range\":\"low\",\"amount\":10}", "\"STRAIGHT\""}) {
            expectProblem(spin(bets(bet)), ProblemCode.MALFORMED_REQUEST);
        }
        for (String body : new String[] {"", "{", "[]", "{\"bets\":\"STRAIGHT\"}", "{\"bets\":{}}"}) {
            expectProblem(spin(body), ProblemCode.MALFORMED_REQUEST);
        }
        verifyNoInteractions(rouletteService);
    }

    // ------------------------------------------------------------------ validation (VALIDATION_FAILED)

    @Test
    void theNumberOfBetsIsOneToTen() throws Exception {
        assertField(spin("{\"bets\":[]}"), "bets", "size must be between 1 and 10");
        assertField(spin("{}"), "bets", "must not be null");
        assertField(spin("{\"bets\":null}"), "bets", "must not be null");
        List<String> eleven = new ArrayList<>();
        for (int n = 0; n <= 10; n++) {
            eleven.add("{\"category\":\"STRAIGHT\",\"number\":" + n + ",\"amount\":1}");
        }
        assertField(spin(bets(eleven.toArray(String[]::new))), "bets", "size must be between 1 and 10");
        assertField(spin("{\"bets\":[null]}"), "bets[0]", "must not be null");
        verifyNoInteractions(rouletteService);
    }

    @Test
    void amountsAreOneToOneThousandAndRequired() throws Exception {
        assertField(spin(bets("{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":0}")), "bets[0].amount",
                "must be greater than or equal to 1");
        assertField(spin(bets("{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":-5}")), "bets[0].amount",
                "must be greater than or equal to 1");
        assertField(spin(bets(RED_20, "{\"category\":\"RED_BLACK\",\"color\":\"BLACK\",\"amount\":1001}")), "bets[1].amount",
                "must be less than or equal to 1000");
        assertField(spin(bets("{\"category\":\"RED_BLACK\",\"color\":\"RED\"}")), "bets[0].amount", "must not be null");
        assertField(spin(bets("{\"color\":\"RED\",\"amount\":10}")), "bets[0].category", "must not be null");
        verifyNoInteractions(rouletteService);
    }

    @Test
    void eachCategoryNeedsExactlyItsOwnOption() throws Exception {
        assertField(spin(bets("{\"category\":\"STRAIGHT\",\"amount\":10}")), "bets[0].number", "must not be null");
        assertField(spin(bets("{\"category\":\"STRAIGHT\",\"number\":null,\"amount\":10}")), "bets[0].number", "must not be null");
        assertField(spin(bets("{\"category\":\"STRAIGHT\",\"number\":37,\"amount\":10}")), "bets[0].number", "must be less than or equal to 36");
        assertField(spin(bets("{\"category\":\"STRAIGHT\",\"number\":-1,\"amount\":10}")), "bets[0].number",
                "must be greater than or equal to 0");
        assertField(spin(bets(RED_20, "{\"category\":\"RED_BLACK\",\"amount\":10}")), "bets[1].color", "must not be null");
        assertField(spin(bets("{\"category\":\"EVEN_ODD\",\"amount\":10}")), "bets[0].parity", "must not be null");
        assertField(spin(bets("{\"category\":\"LOW_HIGH\",\"amount\":10}")), "bets[0].range", "must not be null");
        assertField(spin(bets("{\"category\":\"DOZEN\",\"amount\":10}")), "bets[0].dozen", "must not be null");
        // An option of another category, alone or next to the right one.
        assertField(spin(bets("{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"dozen\":\"FIRST\",\"amount\":10}")), "bets[0].dozen",
                "must be omitted for this category");
        assertField(spin(bets("{\"category\":\"DOZEN\",\"number\":5,\"dozen\":\"FIRST\",\"amount\":10}")), "bets[0].number",
                "must be omitted for this category");
        JsonNode problem = json(expectProblem(spin(bets("{\"category\":\"EVEN_ODD\",\"color\":\"RED\",\"amount\":10}")),
                ProblemCode.VALIDATION_FAILED));
        assertThat(fields(problem)).containsExactly("bets[0].color: must be omitted for this category", "bets[0].parity: must not be null");
        verifyNoInteractions(rouletteService);
    }

    @Test
    void theSameSelectionTwiceIsRejectedWhateverTheAmounts() throws Exception {
        assertField(spin(bets(RED_20, "{\"category\":\"RED_BLACK\",\"color\":\"RED\",\"amount\":5}")), "bets",
                "must not contain the same selection twice");
        assertField(spin(bets("{\"category\":\"STRAIGHT\",\"number\":7,\"amount\":1}", "{\"category\":\"STRAIGHT\",\"number\":7,\"amount\":2}")),
                "bets", "must not contain the same selection twice");
        verifyNoInteractions(rouletteService);
        // Complementary selections are different selections.
        stubAnyRound();
        expect(spin(bets(RED_20, "{\"category\":\"RED_BLACK\",\"color\":\"BLACK\",\"amount\":20}")), 200);
    }

    // ------------------------------------------------------------------ service errors

    @Test
    void serviceErrorsKeepTheirCodesAndUnexpectedOnesAreGeneric() throws Exception {
        doThrow(new InsufficientFundsException()).when(rouletteService).spin(any(), any(), any());
        expectProblem(spin(bets(RED_20)), ProblemCode.INSUFFICIENT_FUNDS);
        doThrow(new IdempotencyKeyReusedException()).when(rouletteService).spin(any(), any(), any());
        expectProblem(spin(bets(RED_20)), ProblemCode.IDEMPOTENCY_KEY_REUSED);
        doThrow(new BalanceLimitExceededException()).when(rouletteService).spin(any(), any(), any());
        expectProblem(spin(bets(RED_20)), ProblemCode.BALANCE_LIMIT_EXCEEDED);
        doThrow(new WalletBusyException()).when(rouletteService).spin(any(), any(), any());
        expectProblem(spin(bets(RED_20)), ProblemCode.SERVICE_UNAVAILABLE);
        doThrow(new IllegalStateException("database is on fire " + PLAYER)).when(rouletteService).spin(any(), any(), any());
        MockHttpServletResponse internal = expectProblem(spin(bets(RED_20)), ProblemCode.INTERNAL_ERROR);
        assertThat(body(internal)).doesNotContain("fire", PLAYER.toString(), "IllegalStateException");
    }

    // ------------------------------------------------------------------ security

    @Test
    void onlyAPlayerWithACsrfTokenMaySpin() throws Exception {
        String body = bets(RED_20);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", KEY).with(csrf()),
                ProblemCode.AUTHENTICATION_REQUIRED);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", KEY)
                .with(user(PLAYER.toString()).roles("ADMIN")).with(csrf()), ProblemCode.ACCESS_DENIED);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", KEY)
                .with(user(PLAYER.toString()).roles("PLAYER")), ProblemCode.CSRF_TOKEN_INVALID);
        expectProblem(post(SPINS).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", KEY)
                .with(user(PLAYER.toString()).roles("PLAYER")).with(csrf().useInvalidToken()), ProblemCode.CSRF_TOKEN_INVALID);
        verifyNoInteractions(rouletteService);
    }

    // ------------------------------------------------------------------ helpers

    private void stubAnyRound() {
        when(rouletteService.spin(any(), any(), any())).thenReturn(new PlayedRound(ROUND, 0, RouletteNumberColor.GREEN,
                List.of(new SettledWager(new Straight(0), 1, 36)), 1, 36, 1035, AT));
    }

    @SuppressWarnings("unchecked")
    private List<RouletteWager> wagers() {
        ArgumentCaptor<List<RouletteWager>> captor = ArgumentCaptor.forClass(List.class);
        verify(rouletteService).spin(eq(PLAYER), eq(UUID.fromString(KEY)), captor.capture());
        return captor.getValue();
    }

    private static String bets(String... bets) {
        return "{\"bets\":[" + String.join(",", bets) + "]}";
    }

    /** A spin request of the player with a valid key and CSRF token. */
    private static MockHttpServletRequestBuilder spin(String body) {
        return player(post(SPINS)).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", KEY);
    }

    private static MockHttpServletRequestBuilder player(MockHttpServletRequestBuilder request) {
        return request.with(user(PLAYER.toString()).roles("PLAYER")).with(csrf());
    }

    private MockHttpServletResponse expect(MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        String body = body(response);
        assertThat(response.getStatus()).as(body).isEqualTo(status);
        assertThat(CONTRACT.validateResponse(SPINS, "POST", status, response.getContentType(), body))
                .as("POST %s %d vs openapi.yaml: %s", SPINS, status, body).isEmpty();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        return response;
    }

    private MockHttpServletResponse expectProblem(MockHttpServletRequestBuilder request, ProblemCode code) throws Exception {
        MockHttpServletResponse response = expect(request, code.status().value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(json(response).path("code").asText()).as(body(response)).isEqualTo(code.name());
        return response;
    }

    /** {@code 400 VALIDATION_FAILED} with exactly one field error. */
    private void assertField(MockHttpServletRequestBuilder request, String field, String message) throws Exception {
        assertThat(fields(json(expectProblem(request, ProblemCode.VALIDATION_FAILED)))).containsExactly(field + ": " + message);
    }

    private static List<String> fields(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.path("errors").forEach(error -> fields.add(error.path("field").asText() + ": " + error.path("message").asText()));
        return fields;
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(body(response));
    }
}
