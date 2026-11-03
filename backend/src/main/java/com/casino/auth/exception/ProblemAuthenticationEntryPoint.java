package com.casino.auth.exception;

import java.io.IOException;

import com.casino.common.exception.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers an anonymous request to a protected path with {@code 401} Problem Details:
 * {@code SESSION_EXPIRED} when the request carried a session cookie whose session no longer exists
 * (inactivity timeout, logout or removal) or whose session was ended during this request (absolute
 * lifetime, {@link #SESSION_ENDED}), otherwise {@code AUTHENTICATION_REQUIRED}.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Request attribute ({@code Boolean.TRUE}): the request's session was ended while it was processed. */
    public static final String SESSION_ENDED = ProblemAuthenticationEntryPoint.class.getName() + ".SESSION_ENDED";

    private final HandlerExceptionResolver exceptionResolver;

    public ProblemAuthenticationEntryPoint(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        boolean sessionEnded = Boolean.TRUE.equals(request.getAttribute(SESSION_ENDED))
                || request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid();
        SecurityProblemException.respond(exceptionResolver, request, response,
                sessionEnded ? ProblemCode.SESSION_EXPIRED : ProblemCode.AUTHENTICATION_REQUIRED);
    }
}
