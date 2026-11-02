package com.casino.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Implements the {@code Authentication} tag of {@code openapi.yaml} (registration, login and logout follow). */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController {

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "getCsrfToken", summary = "Issue a CSRF token")
    @ApiResponse(responseCode = "204", description = "Token cookie set")
    void getCsrfToken(@Parameter(hidden = true) CsrfToken csrfToken) {
        // Spring Security loads the token lazily; reading it writes the XSRF-TOKEN cookie.
        csrfToken.getToken();
    }
}
