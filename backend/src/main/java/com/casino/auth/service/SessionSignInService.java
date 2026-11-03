package com.casino.auth.service;

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
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

/**
 * Signs a user in to the current HTTP session (registration now, login in W2-08), with Spring
 * Security's standard components (week-02.md §7.4):
 * <ol>
 *   <li>a new session id ({@link ChangeSessionIdAuthenticationStrategy}, session-fixation protection);</li>
 *   <li>the CSRF token is cleared ({@link CsrfAuthenticationStrategy}); the client fetches a new one
 *       with {@code GET /api/v1/auth/csrf};</li>
 *   <li>the security context is stored in the session (Spring Session JDBC).</li>
 * </ol>
 *
 * <p>The principal is Spring's {@link User} with the user id as username and {@code ROLE_<role>} as
 * authority. It carries no password, so the session never contains one or its hash. This is the
 * only service that uses the servlet API.
 */
@Service
public class SessionSignInService {

    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionSignInService(CsrfTokenRepository csrfTokenRepository, SecurityContextRepository contextRepository) {
        this.sessionStrategy = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokenRepository)));
        this.contextRepository = contextRepository;
    }

    public void signIn(UUID userId, UserRole role, HttpServletRequest request, HttpServletResponse response) {
        User principal = new User(userId.toString(), "", List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        principal.eraseCredentials();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());

        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
