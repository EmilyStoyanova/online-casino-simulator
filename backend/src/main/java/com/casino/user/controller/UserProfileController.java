package com.casino.user.controller;

import java.security.Principal;
import java.util.UUID;

import com.casino.user.dto.UpdateProfileRequest;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the {@code Users} tag of {@code openapi.yaml}. Requires a session ({@code SecurityConfig}:
 * every {@code /api/**} path that is not public).
 *
 * <p>The user is the session's principal, whose name is the user id; no request value can select
 * another user (AC-9.3).
 */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Users")
class UserProfileController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL = "#/components/schemas/ProblemDetail";

    private final UserProfileService userProfileService;

    UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentUser", summary = "Get the signed-in user's profile",
            security = @SecurityRequirement(name = "sessionCookie"))
    @ApiResponse(responseCode = "200", description = "Profile of the signed-in user")
    @ApiResponse(responseCode = "401", description = "No valid session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    UserProfileResponse getCurrentUser(@Parameter(hidden = true) Principal principal) {
        return userProfileService.getProfile(userId(principal));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateCurrentUser", summary = "Update the signed-in user's profile",
            // A session; OpenApiConfig adds the CSRF token that every unsafe method needs.
            security = @SecurityRequirement(name = "sessionCookie"))
    @ApiResponse(responseCode = "200", description = "Updated profile")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "401", description = "No valid session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "409", description = "The profile was changed concurrently",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    UserProfileResponse updateCurrentUser(@Valid @RequestBody UpdateProfileRequest request,
                                          @Parameter(hidden = true) Principal principal) {
        return userProfileService.updateProfile(userId(principal), request);
    }

    /** The principal's name is the user id ({@code SessionSignInService}). */
    private static UUID userId(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
