package com.casino;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

/** The Spring Session cookie ({@code SESSION}) of test responses. */
public final class SessionCookies {

    private SessionCookies() {
    }

    /** The {@code Set-Cookie} header that issues a session (not one that deletes it). */
    public static String sessionCookie(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith("SESSION=") && !cookie.startsWith("SESSION=;"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in " + response.getHeaders(HttpHeaders.SET_COOKIE)));
    }

    /** The cookie a browser sends back for a {@code Set-Cookie} header. */
    public static Cookie cookie(String setCookie) {
        int end = setCookie.indexOf(';');
        return new Cookie("SESSION", setCookie.substring(setCookie.indexOf('=') + 1, end < 0 ? setCookie.length() : end));
    }

    /** Spring Session stores the session id Base64-encoded in the cookie. */
    public static String sessionId(String setCookie) {
        return new String(Base64.getDecoder().decode(cookie(setCookie).getValue()), StandardCharsets.UTF_8);
    }
}
