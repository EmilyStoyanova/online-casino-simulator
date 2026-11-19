package com.casino.game.controller;

import java.security.Principal;
import java.util.UUID;

import com.casino.game.dto.RouletteSpinRequest;
import com.casino.game.dto.RouletteSpinResult;
import com.casino.game.service.RouletteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the {@code Roulette} tag of {@code openapi.yaml}: one operation, for players only
 * ({@code SecurityConfig}: {@code /api/v1/games/**} requires role {@code PLAYER}; anonymous {@code 401},
 * administrators {@code 403}; the CSRF token is required as for every unsafe method).
 *
 * <p>The player is the session principal; the request carries bets only. The body is validated here (Bean
 * Validation, strict JSON integers), the key is a canonical UUID ({@code CanonicalUuidParameters}), and
 * the round is played, or replayed, by {@link RouletteService#spin} in one transaction. A new round and a
 * replay both answer {@code 200} with the stored round.
 */
@RestController
@RequestMapping("/api/v1/games/roulette")
@Tag(name = "Roulette")
class RouletteController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL = "#/components/schemas/ProblemDetail";

    private final RouletteService rouletteService;

    RouletteController(RouletteService rouletteService) {
        this.rouletteService = rouletteService;
    }

    @PostMapping(path = "/spins", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "spinRoulette", summary = "Place bets and spin the European roulette wheel",
            // A session; OpenApiConfig adds the CSRF token that every unsafe method needs.
            security = @SecurityRequirement(name = "sessionCookie"))
    @ApiResponse(responseCode = "200", description = "The round was played, or the same request was already played")
    @ApiResponse(responseCode = "400", description = "Invalid request; nothing changed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "401", description = "No valid session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token, or not a player",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "409", description = "Insufficient funds, idempotency key reused or balance limit",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "500", description = "Unexpected error; nothing changed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "503", description = "The wallet stayed locked too long; retry with the same key",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    RouletteSpinResult spin(
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "Canonical UUID chosen by the client for this spin attempt; reused for its retries.")
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody RouletteSpinRequest request,
            @Parameter(hidden = true) Principal principal) {
        return RouletteSpinResult.from(rouletteService.spin(userId(principal), idempotencyKey, request.toWagers()));
    }

    /** The principal's name is the user id ({@code SessionSignInService}). */
    private static UUID userId(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
