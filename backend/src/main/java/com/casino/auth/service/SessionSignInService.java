package com.casino.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.casino.user.dto.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

/**
 * Signs a user in to and out of the current HTTP session (registration, login, logout), with Spring
 * Security's standard components (week-02.md §7.4–7.6).
 *
 * <p>Sign-in:
 * <ol>
 *   <li>a new session id ({@link ChangeSessionIdAuthenticationStrategy}, session-fixation protection);</li>
 *   <li>the {@code XSRF-TOKEN} cookie is deleted ({@link CsrfAuthenticationStrategy}); the client
 *       fetches a new token with {@code GET /api/v1/auth/csrf};</li>
 *   <li>{@link #AUTHENTICATED_AT} is stored for the absolute session lifetime
 *       ({@code AbsoluteSessionTimeoutFilter});</li>
 *   <li>the security context is stored in the session (Spring Session JDBC).</li>
 * </ol>
 *
 * <p>Sign-out deletes the {@code XSRF-TOKEN} cookie ({@link CsrfLogoutHandler}) and invalidates the
 * session ({@link SecurityContextLogoutHandler}): Spring Session deletes its row and expires the
 * session cookie. Without a session it does nothing.
 *
 * <p>The principal is Spring's {@link User} with the user id as username and {@code ROLE_<role>} as
 * authority. It carries no password, so the session never contains one or its hash. This is the
 * only service that uses the servlet API.
 */
@Service
public class SessionSignInService {

    /** Session attribute: when the user signed in ({@link Instant}). */
    public static final String AUTHENTICATED_AT = SessionSignInService.class.getName() + ".AUTHENTICATED_AT";

    private final SessionAuthenticationStrategy sessionStrategy;
    private final LogoutHandler logoutHandler;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final Clock clock;

    public SessionSignInService(CsrfTokenRepository csrfTokenRepository, SecurityContextRepository contextRepository, Clock clock) {
        this.sessionStrategy = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokenRepository)));
        SecurityContextLogoutHandler sessionLogout = new SecurityContextLogoutHandler();
        sessionLogout.setSecurityContextRepository(contextRepository);
        this.logoutHandler = new CompositeLogoutHandler(new CsrfLogoutHandler(csrfTokenRepository), sessionLogout);
        this.contextRepository = contextRepository;
        this.clock = clock;
    }

    public void signIn(UUID userId, UserRole role, HttpServletRequest request, HttpServletResponse response) {
        User principal = new User(userId.toString(), "", List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        principal.eraseCredentials();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());

        sessionStrategy.onAuthentication(authentication, request, response);
        request.getSession().setAttribute(AUTHENTICATED_AT, clock.instant());
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        logoutHandler.logout(request, response, contextHolder.getContext().getAuthentication());
    }
}
