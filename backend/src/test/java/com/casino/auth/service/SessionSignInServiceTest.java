package com.casino.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.casino.user.dto.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

class SessionSignInServiceTest {

    private static final UUID USER_ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");

    private final CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
    private final SessionSignInService service = new SessionSignInService(csrfTokens, new HttpSessionSecurityContextRepository());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void storesThePlayerWithoutCredentialsInANewSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        // The client arrives with an existing session (e.g. of another account).
        MockHttpSession existing = new MockHttpSession();
        request.setSession(existing);
        request.setRequestedSessionId(existing.getId());
        request.setRequestedSessionIdValid(true);
        String idBefore = existing.getId();

        service.signIn(USER_ID, UserRole.PLAYER, request, new MockHttpServletResponse());

        // Session fixation protection: the session id changed.
        assertThat(request.getSession(false).getId()).isNotEqualTo(idBefore);
        SecurityContext stored = (SecurityContext) request.getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        Authentication authentication = stored.getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(USER_ID.toString());
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_PLAYER");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(((User) authentication.getPrincipal()).getPassword()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }

    @Test
    void clearsTheCsrfTokenOfTheAnonymousClient() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setCookies(new Cookie("XSRF-TOKEN", "old-token"));

        service.signIn(USER_ID, UserRole.ADMIN, request, response);

        // The old cookie is expired; the client fetches a new token with GET /api/v1/auth/csrf.
        assertThat(response.getCookie("XSRF-TOKEN")).isNotNull()
                .satisfies(cookie -> assertThat(cookie.getMaxAge()).isZero());
    }
}
