package com.casino.auth.controller;

import com.casino.auth.dto.LoginRequest;
import com.casino.auth.dto.RegisterRequest;
import com.casino.auth.service.LoginService;
import com.casino.auth.service.RegistrationService;
import com.casino.auth.service.SessionSignInService;
import com.casino.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Implements the {@code Authentication} tag of {@code openapi.yaml}. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL = "#/components/schemas/ProblemDetail";

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final SessionSignInService sessionSignInService;

    AuthController(RegistrationService registrationService, LoginService loginService,
                   SessionSignInService sessionSignInService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.sessionSignInService = sessionSignInService;
    }

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "getCsrfToken", summary = "Issue a CSRF token")
    @ApiResponse(responseCode = "204", description = "Token cookie set")
    void getCsrfToken(@Parameter(hidden = true) CsrfToken csrfToken) {
        // Spring Security loads the token lazily; reading it writes the XSRF-TOKEN cookie.
        csrfToken.getToken();
    }

    /**
     * Creates the account, then signs the user in. The attempt has already been counted by
     * {@link RegistrationAttemptInterceptor}, before the body was validated.
     */
    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "register", summary = "Register a player account and sign in",
            security = @SecurityRequirement(name = "csrfHeader"))
    @ApiResponse(responseCode = "201", description = "Account created; the user is signed in")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token, or registration is closed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "409", description = "The email is already registered",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "429", description = "Too many registration attempts",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    UserProfileResponse register(@Valid @RequestBody RegisterRequest request,
                                 @Parameter(hidden = true) HttpServletRequest httpRequest,
                                 @Parameter(hidden = true) HttpServletResponse httpResponse) {
        UserProfileResponse profile = registrationService.register(request);
        sessionSignInService.signIn(profile.id(), profile.role(), httpRequest, httpResponse);
        return profile;
    }

    /** Verifies the credentials (throttled per account + IP and per IP), then signs the user in. */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "login", summary = "Sign in", security = @SecurityRequirement(name = "csrfHeader"))
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "401", description = "Sign-in failed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    @ApiResponse(responseCode = "429", description = "Too many failed logins",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    UserProfileResponse login(@Valid @RequestBody LoginRequest request,
                              @Parameter(hidden = true) HttpServletRequest httpRequest,
                              @Parameter(hidden = true) HttpServletResponse httpResponse) {
        UserProfileResponse profile = loginService.login(request, httpRequest.getRemoteAddr());
        sessionSignInService.signIn(profile.id(), profile.role(), httpRequest, httpResponse);
        return profile;
    }

    /** Ends the session, if there is one, and deletes the session and CSRF cookies. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "logout", summary = "Sign out", security = @SecurityRequirement(name = "csrfHeader"))
    @ApiResponse(responseCode = "204", description = "Signed out (or no session existed)")
    @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL)))
    void logout(@Parameter(hidden = true) HttpServletRequest httpRequest,
                @Parameter(hidden = true) HttpServletResponse httpResponse) {
        sessionSignInService.signOut(httpRequest, httpResponse);
    }
}
