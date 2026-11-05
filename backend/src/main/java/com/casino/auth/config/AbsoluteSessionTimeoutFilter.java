package com.casino.auth.config;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import com.casino.auth.exception.ProblemAuthenticationEntryPoint;
import com.casino.auth.service.SessionSignInService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Absolute session lifetime (AC-5.3): a signed-in session older than the configured lifetime
 * ({@code app.security.session.absolute-timeout}, 12 h) is invalidated on its next request, however
 * active it was. The request then continues as anonymous, so a protected operation answers
 * {@code 401 SESSION_EXPIRED}.
 *
 * <p>Runs in the security filter chain before the security context is loaded. It reads only the
 * session that Spring Session loads for the request anyway, so it adds no database query; requests
 * without a session cookie are not affected and no session is created. A signed-in session without
 * {@link SessionSignInService#AUTHENTICATED_AT} (e.g. created before this filter existed) is treated
 * as expired.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Duration absoluteTimeout;
    private final Supplier<Clock> clock;

    public AbsoluteSessionTimeoutFilter(Duration absoluteTimeout, Supplier<Clock> clock) {
        this.absoluteTimeout = absoluteTimeout;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && expired(session)) {
            session.invalidate();
            request.setAttribute(ProblemAuthenticationEntryPoint.SESSION_ENDED, Boolean.TRUE);
        }
        chain.doFilter(request, response);
    }

    private boolean expired(HttpSession session) {
        Object authenticatedAt = session.getAttribute(SessionSignInService.AUTHENTICATED_AT);
        if (authenticatedAt instanceof Instant signedIn) {
            return !clock.get().instant().isBefore(signedIn.plus(absoluteTimeout));
        }
        // Signed in, but without a sign-in time: the lifetime cannot be checked.
        return session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
    }
}
