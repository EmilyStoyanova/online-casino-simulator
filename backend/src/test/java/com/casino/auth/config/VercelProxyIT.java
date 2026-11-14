package com.casino.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import com.casino.TestAccounts;
import com.casino.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The frontend on Vercel calls the API on its own origin; a Vercel rewrite forwards {@code /api/*} to
 * Render ({@code frontend/vercel.json}). The backend then sees what this test sends:
 * <ul>
 *   <li>a {@code Host} that is not the frontend's (Vercel sets the Render host), so the browser's
 *       {@code Origin} on unsafe requests makes them CORS requests, allowed only for the exact
 *       origin in {@code APP_CORS_ALLOWED_ORIGINS};</li>
 *   <li>Vercel's forwarded headers, which the profile {@code render} ignores;</li>
 *   <li>the browser's cookies, which belong to the frontend's host: no {@code Domain} attribute, so
 *       they work under any host the proxy serves.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins=" + VercelProxyIT.FRONTEND)
@ActiveProfiles("render")
@Import(TestcontainersConfiguration.class)
class VercelProxyIT {

    static final String FRONTEND = "https://casino-simulator.vercel.app";
    private static final String PASSWORD = "correct-horse-battery-5e2a";
    /** What Vercel adds to a rewritten request. */
    private static final Map<String, String> VERCEL = Map.of(
            "X-Forwarded-For", "203.0.113.50",
            "X-Real-IP", "203.0.113.50",
            "X-Forwarded-Host", "casino-simulator.vercel.app",
            "X-Forwarded-Proto", "https",
            "X-Vercel-Id", "fra1::abcde-1700000000000-0123456789ab");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** The browser's cookies for the frontend's host. */
    private final Map<String, String> cookies = new HashMap<>();

    @AfterEach
    void deleteRows() {
        // The render profile is active, so TestDatabase.reset() refuses (G4). Registration is closed under
        // render and these users are inserted directly, so none has a wallet and DELETE still works.
        new TestAccounts(jdbcTemplate, passwordEncoder).deleteAll();
    }

    @Test
    void signInProfileAndSignOutWorkThroughTheRewriteFromTheConfiguredOrigin() throws Exception {
        new TestAccounts(jdbcTemplate, passwordEncoder).active("ivan@example.com", PASSWORD);

        // Same-origin GETs carry no Origin header.
        HttpResponse<String> csrf = send(get("/api/v1/auth/csrf"));
        assertThat(csrf.statusCode()).isEqualTo(204);
        assertThat(cookie(csrf, "XSRF-TOKEN")).contains("; Path=/", "; Secure").doesNotContain("Domain=", "HttpOnly");

        HttpResponse<String> login = send(post("/api/v1/auth/login",
                "{\"email\":\"ivan@example.com\",\"password\":\"" + PASSWORD + "\"}", FRONTEND));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(cookie(login, "__Host-SESSION")).contains("; Path=/", "; Secure", "; HttpOnly", "; SameSite=Lax")
                .doesNotContain("Domain=");
        assertThat(login.headers().firstValue("Strict-Transport-Security")).isPresent();

        HttpResponse<String> me = send(get("/api/v1/users/me"));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"ivan@example.com\"");

        // Login deleted the CSRF cookie: a new token, as the frontend fetches it.
        assertThat(send(get("/api/v1/auth/csrf")).statusCode()).isEqualTo(204);
        HttpResponse<String> logout = send(post("/api/v1/auth/logout", null, FRONTEND));
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(cookie(logout, "__Host-SESSION")).contains("Max-Age=0", "; Path=/", "; Secure");

        assertThat(send(get("/api/v1/users/me")).statusCode()).isEqualTo(401);
    }

    @Test
    void otherOriginsCannotSignInWithAValidCsrfTokenAndPassword() throws Exception {
        new TestAccounts(jdbcTemplate, passwordEncoder).active("judy@example.com", PASSWORD);
        String body = "{\"email\":\"judy@example.com\",\"password\":\"" + PASSWORD + "\"}";

        // A preview deployment, a look-alike site, and the opaque "null" origin (sandboxed frames,
        // and per the Fetch standard POSTs from a page with `Referrer-Policy: no-referrer`).
        for (String origin : new String[] {"https://casino-simulator-git-feature-x.vercel.app",
                "https://casino-simulator.vercel.app.evil.example", "null"}) {
            assertThat(send(get("/api/v1/auth/csrf")).statusCode()).isEqualTo(204);

            HttpResponse<String> login = send(post("/api/v1/auth/login", body, origin));

            assertThat(login.statusCode()).as(origin).isEqualTo(403);
            assertThat(login.body()).isEqualTo("Invalid CORS request");
            assertThat(login.headers().allValues("Set-Cookie")).as(origin).noneMatch(c -> c.startsWith("__Host-SESSION="));
        }
        assertThat(cookies).doesNotContainKey("__Host-SESSION");
    }

    // ------------------------------------------------------------------ helpers

    private HttpRequest get(String path) {
        return builder(path).GET().build();
    }

    /** An unsafe request as the SPA sends it: JSON, the CSRF header, and the browser's {@code Origin}. */
    private HttpRequest post(String path, String json, String origin) {
        HttpRequest.Builder builder = builder(path)
                .header("Origin", origin)
                .header("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", ""));
        if (json == null) {
            return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json, application/problem+json");
        VERCEL.forEach(builder::header);
        if (!cookies.isEmpty()) {
            builder.header("Cookie", String.join("; ", cookies.entrySet().stream()
                    .map(cookie -> cookie.getKey() + "=" + cookie.getValue()).toList()));
        }
        return builder;
    }

    /** Sends the request and keeps or deletes cookies as the browser would. */
    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.substring(0, header.indexOf(';'));
            String name = pair.substring(0, pair.indexOf('='));
            String value = pair.substring(pair.indexOf('=') + 1);
            if (value.isEmpty() || header.contains("Max-Age=0") || header.contains("Expires=Thu, 01 Jan 1970")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return response;
    }

    /** The {@code Set-Cookie} header of the named cookie. */
    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(header -> header.startsWith(name + "="))
                .findFirst().orElseThrow(() -> new AssertionError("No Set-Cookie for " + name));
    }
}
