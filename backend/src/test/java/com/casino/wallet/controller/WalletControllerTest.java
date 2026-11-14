package com.casino.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.casino.auth.config.SecurityConfig;
import com.casino.common.config.WebConfig;
import com.casino.common.exception.ProblemCode;
import com.casino.contract.OpenApiContract;
import com.casino.wallet.dto.WalletEntryType;
import com.casino.wallet.dto.WalletResponse;
import com.casino.wallet.dto.WalletTransactionPage;
import com.casino.wallet.dto.WalletTransactionResponse;
import com.casino.wallet.exception.InvalidCursorException;
import com.casino.wallet.exception.WalletNotFoundException;
import com.casino.wallet.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;
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
 * {@code GET /api/v1/wallet} and {@code GET /api/v1/wallet/transactions} through the real security
 * filter chain with a mocked service: the role rule, parameter binding and validation, error mapping
 * and the contract.
 */
@WebMvcTest(WalletController.class)
@Import({SecurityConfig.class, WebConfig.class})
class WalletControllerTest {

    private static final String WALLET = "/api/v1/wallet";
    private static final String TRANSACTIONS = "/api/v1/wallet/transactions";
    private static final UUID ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");
    private static final Instant AT = Instant.parse("2026-11-12T09:15:00Z");
    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletService walletService;

    @Test
    void theWalletOfTheSessionsPlayerIsReturned() throws Exception {
        when(walletService.getWallet(ID)).thenReturn(new WalletResponse(1000, AT));

        MockHttpServletResponse response = expect(WALLET, player(get(WALLET)), 200);

        assertThat(body(response)).isEqualTo("{\"balance\":1000,\"updatedAt\":\"2026-11-12T09:15:00Z\"}");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void aPageAlwaysHasItemsAndNextCursorAndUsesTheDefaultLimit() throws Exception {
        when(walletService.listTransactions(ID, null, 20)).thenReturn(new WalletTransactionPage(
                List.of(new WalletTransactionResponse(1, WalletEntryType.STARTING_BALANCE, 1000, 1000, AT)), null));

        MockHttpServletResponse response = expect(TRANSACTIONS, player(get(TRANSACTIONS)), 200);

        // nextCursor is present as null on the last page (required and nullable).
        assertThat(body(response)).isEqualTo("""
                {"items":[{"id":1,"type":"STARTING_BALANCE","amount":1000,"balanceAfter":1000,\
                "createdAt":"2026-11-12T09:15:00Z"}],"nextCursor":null}""");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void limitAndCursorArePassedOnAndTheBoundsAreAccepted() throws Exception {
        when(walletService.listTransactions(eq(ID), any(), anyInt())).thenReturn(new WalletTransactionPage(List.of(), "djE6NDI"));

        expect(TRANSACTIONS, player(get(TRANSACTIONS).param("limit", "1")), 200);
        expect(TRANSACTIONS, player(get(TRANSACTIONS).param("limit", "100").param("cursor", "djE6NDI")), 200);
        // Decimal digits only (DecimalIntegerParameters); leading zeros are still decimal.
        expect(TRANSACTIONS, player(get(TRANSACTIONS).param("limit", "5")), 200);
        expect(TRANSACTIONS, player(get(TRANSACTIONS).param("limit", "007")), 200);

        verify(walletService).listTransactions(ID, null, 1);
        verify(walletService).listTransactions(ID, "djE6NDI", 100);
        verify(walletService).listTransactions(ID, null, 5);
        verify(walletService).listTransactions(ID, null, 7);
    }

    @Test
    void aLimitOutsideOneToOneHundredIsAValidationError() throws Exception {
        for (String limit : new String[] {"0", "-1", "101", "2147483647"}) {
            MockHttpServletResponse response = expectProblem(TRANSACTIONS, player(get(TRANSACTIONS).param("limit", limit)),
                    ProblemCode.VALIDATION_FAILED);
            List<String> fields = new ArrayList<>();
            json(response).path("errors").forEach(error -> fields.add(error.path("field").asText()));
            assertThat(fields).as(limit).containsExactly("limit");
        }
        assertThat(json(expectProblem(TRANSACTIONS, player(get(TRANSACTIONS).param("limit", "101")), ProblemCode.VALIDATION_FAILED))
                .path("errors").get(0).path("message").asText()).isEqualTo("must be less than or equal to 100");
        verifyNoInteractions(walletService);
    }

    @Test
    void aLimitThatIsNotADecimalIntegerIsMalformed() throws Exception {
        for (String limit : new String[] {"ten", "1.5", "1e2", "99999999999", "0x10", "#10", "+5", " 5", "5 ", "\u0665"}) {
            expectProblem(TRANSACTIONS, player(get(TRANSACTIONS).param("limit", limit)), ProblemCode.MALFORMED_REQUEST);
        }
        verifyNoInteractions(walletService);
    }

    @Test
    void anInvalidCursorIsAValidationErrorWithoutEchoingIt() throws Exception {
        when(walletService.listTransactions(ID, "forged", 20)).thenThrow(new InvalidCursorException());

        MockHttpServletResponse response = expectProblem(TRANSACTIONS, player(get(TRANSACTIONS).param("cursor", "forged")),
                ProblemCode.VALIDATION_FAILED);

        assertThat(json(response).path("errors").toString()).isEqualTo("[{\"field\":\"cursor\",\"message\":\"is not a valid cursor\"}]");
        assertThat(body(response)).doesNotContain("forged");
    }

    @Test
    void requestValuesNeverSelectAnotherUser() throws Exception {
        when(walletService.listTransactions(ID, null, 20)).thenReturn(new WalletTransactionPage(List.of(), null));
        when(walletService.getWallet(ID)).thenReturn(new WalletResponse(5, AT));
        String other = UUID.randomUUID().toString();

        expect(TRANSACTIONS, player(get(TRANSACTIONS).param("userId", other).param("walletId", other)), 200);
        expect(WALLET, player(get(WALLET).param("userId", other)), 200);

        verify(walletService).listTransactions(ID, null, 20);
        verify(walletService).getWallet(ID);
    }

    @Test
    void anonymousUsersAreUnauthenticatedAndNonPlayersForbidden() throws Exception {
        for (String path : List.of(WALLET, TRANSACTIONS)) {
            expectProblem(path, get(path), ProblemCode.AUTHENTICATION_REQUIRED);
            expectProblem(path, get(path).with(user(ID.toString()).roles("ADMIN")), ProblemCode.ACCESS_DENIED);
        }
        verifyNoInteractions(walletService);
    }

    @Test
    void aPlayerWithoutAWalletIsAGenericInternalError() throws Exception {
        when(walletService.listTransactions(eq(ID), isNull(), anyInt())).thenThrow(new WalletNotFoundException(ID));
        when(walletService.getWallet(ID)).thenThrow(new WalletNotFoundException(ID));

        for (String path : List.of(WALLET, TRANSACTIONS)) {
            assertThat(body(expectProblem(path, player(get(path)), ProblemCode.INTERNAL_ERROR))).doesNotContain(ID.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder player(MockHttpServletRequestBuilder request) {
        return request.with(user(ID.toString()).roles("PLAYER"));
    }

    private MockHttpServletResponse expect(String path, MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        String body = body(response);

        assertThat(response.getStatus()).as(body).isEqualTo(status);
        assertThat(CONTRACT.validateResponse(path, "GET", status, response.getContentType(), body))
                .as("GET %s %d vs openapi.yaml", path, status).isEmpty();
        return response;
    }

    private MockHttpServletResponse expectProblem(String path, MockHttpServletRequestBuilder request, ProblemCode code) throws Exception {
        MockHttpServletResponse response = expect(path, request, code.status().value());

        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(json(response).path("code").asText()).isEqualTo(code.name());
        return response;
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return Json31.mapper().readTree(body(response));
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
