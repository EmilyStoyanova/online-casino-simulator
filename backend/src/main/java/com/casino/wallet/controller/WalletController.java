package com.casino.wallet.controller;

import java.security.Principal;
import java.util.UUID;

import com.casino.wallet.dto.WalletResponse;
import com.casino.wallet.dto.WalletTransactionPage;
import com.casino.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the {@code Wallet} tag of {@code openapi.yaml}: read-only, for players only
 * ({@code SecurityConfig}: {@code /api/v1/wallet/**} requires role {@code PLAYER}; anonymous
 * {@code 401}, administrators {@code 403}).
 *
 * <p>The wallet is the session principal's, whose name is the user id; no request value can select
 * another user's wallet or ledger (AC-2.1, AC-3.1). No operation here credits or debits a wallet (P-W3-9).
 */
@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet")
class WalletController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL = "#/components/schemas/ProblemDetail";
    private static final int MAX_PAGE_SIZE = 100;

    private final WalletService walletService;

    WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getWallet", summary = "Get the signed-in player's wallet",
            security = @SecurityRequirement(name = "sessionCookie"))
    @ApiResponse(responseCode = "200", description = "Wallet of the signed-in player")
    @ApiResponse(responseCode = "401", description = "No valid session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "403", description = "The signed-in user is not a player",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "500", description = "Unexpected error, including a player without a wallet",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    WalletResponse getWallet(@Parameter(hidden = true) Principal principal) {
        return walletService.getWallet(userId(principal));
    }

    @GetMapping(path = "/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listWalletTransactions", summary = "List the signed-in player's wallet transactions",
            security = @SecurityRequirement(name = "sessionCookie"))
    @ApiResponse(responseCode = "200", description = "One page of ledger entries, newest first")
    @ApiResponse(responseCode = "400", description = "Invalid limit or cursor",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "401", description = "No valid session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "403", description = "The signed-in user is not a player",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "500", description = "Unexpected error, including a player without a wallet",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    WalletTransactionPage listWalletTransactions(
            // Out of range: 400 VALIDATION_FAILED (field limit); not an integer: 400 MALFORMED_REQUEST.
            @Parameter(description = "Maximum number of entries in the page.",
                    schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int limit,
            // Checked by the service: anything but an issued cursor is 400 VALIDATION_FAILED (field cursor).
            @Parameter(description = "The nextCursor of the previous page; omitted for the first page.")
            @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(hidden = true) Principal principal) {
        return walletService.listTransactions(userId(principal), cursor, limit);
    }

    /** The principal's name is the user id ({@code SessionSignInService}). */
    private static UUID userId(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
