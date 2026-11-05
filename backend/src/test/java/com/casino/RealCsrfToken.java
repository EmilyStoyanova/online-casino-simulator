package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A real CSRF token from {@code GET /api/v1/auth/csrf}, sent as the SPA does: the {@code XSRF-TOKEN}
 * cookie and the {@code X-XSRF-TOKEN} header.
 *
 * <p>Used instead of spring-security-test's {@code csrf()}, which replaces the token repository of the
 * shared {@code CsrfFilter} for the rest of the cached application context; after that,
 * {@code GET /api/v1/auth/csrf} no longer sets a cookie in that context.
 */
public final class RealCsrfToken implements RequestPostProcessor {

    public static final String COOKIE = "XSRF-TOKEN";
    public static final String HEADER = "X-XSRF-TOKEN";

    private final Cookie cookie;
    private final String headerValue;

    private RealCsrfToken(Cookie cookie, String headerValue) {
        this.cookie = cookie;
        this.headerValue = headerValue;
    }

    public static RealCsrfToken fetch(MockMvc mockMvc) throws Exception {
        Cookie cookie = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie(COOKIE);
        assertThat(cookie).as("XSRF-TOKEN cookie of GET /api/v1/auth/csrf").isNotNull();
        return new RealCsrfToken(cookie, cookie.getValue());
    }

    /** The same cookie with a header that does not match it. */
    public RealCsrfToken withWrongHeader() {
        return new RealCsrfToken(cookie, "wrong-" + headerValue);
    }

    public Cookie cookie() {
        return cookie;
    }

    @Override
    public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
        List<Cookie> cookies = new ArrayList<>(request.getCookies() == null ? List.of() : Arrays.asList(request.getCookies()));
        cookies.add(cookie);
        request.setCookies(cookies.toArray(Cookie[]::new));
        request.addHeader(HEADER, headerValue);
        return request;
    }
}
