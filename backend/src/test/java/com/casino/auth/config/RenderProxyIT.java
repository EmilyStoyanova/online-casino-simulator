package com.casino.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
 * The profile {@code render} over real HTTP with the embedded Tomcat, as Render's proxy delivers
 * requests: TLS ends at the proxy, so they arrive as plain HTTP, and clients can add any forwarded
 * header (Render does not strip them). HSTS and {@code Secure} cookies must not depend on those
 * headers, and none of them may change the client address that throttling uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("render")
@Import({TestcontainersConfiguration.class, ClientAddressIT.ProbeConfiguration.class})
class RenderProxyIT {

    private static final String PASSWORD = "correct-horse-battery-91c4";
    private static final String HSTS = "max-age=31536000 ; includeSubDomains";
    /** Every forwarded header a client could send to make itself look like another address or HTTPS. */
    private static final Map<String, String> FORGED = Map.of(
            "X-Forwarded-For", "6.6.6.6",
            "X-Forwarded-Proto", "http",
            "X-Forwarded-Host", "evil.example",
            "X-Forwarded-Port", "8443",
            "Forwarded", "for=6.6.6.7;proto=https;host=evil.example",
            "X-Real-IP", "6.6.6.8",
            "CF-Connecting-IP", "6.6.6.9",
            "True-Client-IP", "6.6.6.10");

    @LocalServerPort
    private int port;

    @Autowired
    private ClientAddressIT.ClientAddressProbe probe;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void deleteRows() {
        new TestAccounts(jdbcTemplate, passwordEncoder).deleteAll();
    }

    @Test
    void everyResponseHasHstsAndTheCsrfCookieIsSecureWithoutTrustingForwardedHeaders() throws Exception {
        HttpResponse<String> csrf = send(get("/api/v1/auth/csrf", FORGED));

        assertThat(csrf.statusCode()).isEqualTo(204);
        assertThat(csrf.headers().firstValue("Strict-Transport-Security")).hasValue(HSTS);
        assertThat(cookie(csrf, "XSRF-TOKEN")).contains("; Path=/", "; Secure").doesNotContain("HttpOnly");
        // The request itself stays what it is: plain HTTP from the TCP peer.
        assertThat(probe.remoteAddr).isEqualTo("127.0.0.1");
        assertThat(probe.secure).isFalse();

        for (String path : List.of("/api/v1/system/status", "/actuator/health/readiness", "/api/v1/unknown")) {
            assertThat(send(get(path, Map.of())).headers().firstValue("Strict-Transport-Security")).as(path).hasValue(HSTS);
        }
    }

    @Test
    void theSessionCookieIsHostPrefixedSecureHttpOnlyAndLax() throws Exception {
        new TestAccounts(jdbcTemplate, passwordEncoder).active("grace@example.com", PASSWORD);
        String token = csrfToken();

        HttpResponse<String> login = send(login("grace@example.com", PASSWORD, token, FORGED));

        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(cookie(login, "__Host-SESSION")).contains("; Path=/", "; Secure", "; HttpOnly", "; SameSite=Lax")
                .doesNotContain("Domain=");
        // Signing in deletes the CSRF cookie; the deletion carries Secure too.
        assertThat(cookie(login, "XSRF-TOKEN")).startsWith("XSRF-TOKEN=;").contains("Expires=Thu, 01 Jan 1970", "; Secure");
    }

    @Test
    void forgedForwardedHeadersCannotEscapeLoginThrottling() throws Exception {
        new TestAccounts(jdbcTemplate, passwordEncoder).active("heidi@example.com", PASSWORD);
        String token = csrfToken();

        for (int attempt = 1; attempt <= 5; attempt++) {
            // A different forged address in every header and every attempt.
            Map<String, String> forged = Map.of("X-Forwarded-For", "198.51.100." + attempt,
                    "Forwarded", "for=198.51.101." + attempt, "X-Real-IP", "198.51.102." + attempt,
                    "CF-Connecting-IP", "198.51.103." + attempt, "True-Client-IP", "198.51.104." + attempt);
            assertThat(send(login("heidi@example.com", "wrong-password-0000", token, forged)).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> blocked = send(login("heidi@example.com", PASSWORD, token,
                Map.of("X-Forwarded-For", "203.0.113.200", "CF-Connecting-IP", "203.0.113.201")));

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("\"code\":\"TOO_MANY_ATTEMPTS\"");
        assertThat(probe.remoteAddr).isEqualTo("127.0.0.1");
    }

    @Test
    void registrationIsClosedByDefault() throws Exception {
        String token = csrfToken();

        HttpResponse<String> register = send(post("/api/v1/auth/register", token, "{}", FORGED));

        assertThat(register.statusCode()).isEqualTo(403);
        assertThat(register.body()).contains("\"code\":\"REGISTRATION_DISABLED\"");
        assertThat(register.headers().firstValue("Strict-Transport-Security")).hasValue(HSTS);
    }

    // ------------------------------------------------------------------ helpers

    private String csrfToken() throws Exception {
        String cookie = cookie(send(get("/api/v1/auth/csrf", Map.of())), "XSRF-TOKEN");
        return cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
    }

    private HttpRequest get(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        headers.forEach(builder::header);
        return builder.build();
    }

    private HttpRequest login(String email, String password, String token, Map<String, String> headers) {
        return post("/api/v1/auth/login", token, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", headers);
    }

    private HttpRequest post(String path, String token, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return builder.build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** The {@code Set-Cookie} header of the named cookie. */
    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(header -> header.startsWith(name + "="))
                .findFirst().orElseThrow(() -> new AssertionError("No Set-Cookie for " + name));
    }
}
