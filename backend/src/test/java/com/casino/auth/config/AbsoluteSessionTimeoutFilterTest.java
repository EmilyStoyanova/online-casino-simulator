package com.casino.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.casino.auth.exception.ProblemAuthenticationEntryPoint;
import com.casino.auth.service.MutableClock;
import com.casino.auth.service.SessionSignInService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class AbsoluteSessionTimeoutFilterTest {

    private static final Instant SIGNED_IN = Instant.parse("2026-11-03T08:00:00Z");
    private static final Duration LIFETIME = Duration.ofHours(12);

    private final MutableClock clock = new MutableClock(SIGNED_IN);
    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(LIFETIME, () -> clock);

    @Test
    void aSessionWithinItsLifetimeIsKept() throws Exception {
        clock.advance(LIFETIME.minusSeconds(1));
        MockHttpServletRequest request = requestWith(signedInSession(SIGNED_IN));

        MockFilterChain chain = filter(request);

        assertThat(((MockHttpSession) request.getSession(false)).isInvalid()).isFalse();
        assertThat(request.getAttribute(ProblemAuthenticationEntryPoint.SESSION_ENDED)).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void aSessionIsEndedWhenItsLifetimeIsOverAndTheRequestContinues() throws Exception {
        clock.advance(LIFETIME);
        MockHttpSession session = signedInSession(SIGNED_IN);
        MockHttpServletRequest request = requestWith(session);

        MockFilterChain chain = filter(request);

        assertThat(session.isInvalid()).isTrue();
        assertThat(request.getAttribute(ProblemAuthenticationEntryPoint.SESSION_ENDED)).isEqualTo(Boolean.TRUE);
        // Continues as anonymous: protected operations answer 401 SESSION_EXPIRED.
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void aSignedInSessionWithoutSignInTimeIsEnded() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl());

        filter(requestWith(session));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anAnonymousSessionIsKept() throws Exception {
        MockHttpSession session = new MockHttpSession();

        filter(requestWith(session));

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void noSessionIsCreated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/status");

        filter(request);

        assertThat(request.getSession(false)).isNull();
    }

    private MockFilterChain filter(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    private static MockHttpSession signedInSession(Instant signedIn) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl());
        session.setAttribute(SessionSignInService.AUTHENTICATED_AT, signedIn);
        return session;
    }

    private static MockHttpServletRequest requestWith(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wallet");
        request.setSession(session);
        return request;
    }
}
