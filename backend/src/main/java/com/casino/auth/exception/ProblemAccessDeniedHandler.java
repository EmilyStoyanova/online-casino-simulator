package com.casino.auth.exception;

import java.io.IOException;

import com.casino.common.exception.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers a denied request with {@code 403} Problem Details: {@code CSRF_TOKEN_INVALID} when the CSRF
 * token is missing or does not match, otherwise {@code ACCESS_DENIED} (authenticated, but not allowed).
 */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final HandlerExceptionResolver exceptionResolver;

    public ProblemAccessDeniedHandler(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        SecurityProblemException.respond(exceptionResolver, request, response,
                accessDeniedException instanceof CsrfException ? ProblemCode.CSRF_TOKEN_INVALID : ProblemCode.ACCESS_DENIED);
    }
}
