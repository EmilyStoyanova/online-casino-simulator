package com.casino.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.casino.Concurrency;
import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestcontainersConfiguration;
import com.casino.auth.service.ThrottleKeys;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Login throttling end to end (FR-6, S1, S2, S12, S13) with PostgreSQL counters. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ThrottleIT {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "correct-horse-battery-5b2d";
    private static final String WRONG = "wrong-password-1234";

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ThrottleKeys throttleKeys;

    private TestAccounts accounts;
    private RealCsrfToken csrf;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        csrf = RealCsrfToken.fetch(mockMvc);
    }

    @AfterEach
    void deleteRows() {
        accounts.deleteAll();
    }

    @Test
    void fiveFailuresBlockTheAccountFromThatAddressOnly() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(login("alice@example.com", WRONG, "192.0.2.40").getStatus()).isEqualTo(401);
        }

        // AC-6.1, AC-6.5: blocked even with the right password, which is not verified any more.
        MockHttpServletResponse throttled = login("alice@example.com", PASSWORD, "192.0.2.40");
        assertThat(throttled.getStatus()).isEqualTo(429);
        assertThat(Json31.mapper().readTree(throttled.getContentAsString()).path("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(Long.parseLong(throttled.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(850L, 900L);
        assertThat(throttled.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("SESSION="));
        // AC-6.2: no account lockout; the same account from another address still works.
        assertThat(login("alice@example.com", PASSWORD, "192.0.2.41").getStatus()).isEqualTo(200);
    }

    @Test
    void unknownAccountsAreThrottledTheSameWay() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        for (int i = 0; i < 5; i++) {
            login("alice@example.com", WRONG, "192.0.2.42");
            login("nobody@example.com", WRONG, "192.0.2.43");
        }

        // AC-6.4: the same status, body and Retry-After for an existing and an unknown account.
        MockHttpServletResponse existing = login("alice@example.com", WRONG, "192.0.2.42");
        MockHttpServletResponse unknown = login("nobody@example.com", WRONG, "192.0.2.43");
        assertThat(unknown.getStatus()).isEqualTo(existing.getStatus()).isEqualTo(429);
        assertThat(unknown.getContentAsString()).isEqualTo(existing.getContentAsString());
        assertThat(Long.parseLong(unknown.getHeader(HttpHeaders.RETRY_AFTER)))
                .isCloseTo(Long.parseLong(existing.getHeader(HttpHeaders.RETRY_AFTER)), within(2L));
    }

    @Test
    void aSuccessfulLoginResetsTheAccountAndAddressCounter() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        for (int i = 0; i < 4; i++) {
            login("alice@example.com", WRONG, "192.0.2.44");
        }
        assertThat(login("alice@example.com", PASSWORD, "192.0.2.44").getStatus()).isEqualTo(200);

        // AC-6.6: 4 new failures do not reach the limit of 5.
        for (int i = 0; i < 4; i++) {
            assertThat(login("alice@example.com", WRONG, "192.0.2.44").getStatus()).isEqualTo(401);
        }
        assertThat(login("alice@example.com", PASSWORD, "192.0.2.44").getStatus()).isEqualTo(200);
        // The address counter is not reset by a success: 8 failures so far.
        assertThat(attempts(throttleKeys.loginIp("192.0.2.44").value())).isEqualTo(8);
    }

    @Test
    void fiftyFailuresFromOneAddressBlockItForEveryAccount() throws Exception {
        accounts.active("alice@example.com", PASSWORD);

        // AC-6.3: credential stuffing, 50 accounts once each, in parallel; every failure is counted exactly.
        AtomicInteger user = new AtomicInteger();
        List<Integer> statuses = Concurrency.runTogether(50, () -> login("user" + user.incrementAndGet()
                + "@example.com", WRONG, "192.0.2.45").getStatus());
        assertThat(statuses).containsOnly(401);
        assertThat(attempts(throttleKeys.loginIp("192.0.2.45").value())).isEqualTo(50);

        assertThat(login("alice@example.com", PASSWORD, "192.0.2.45").getStatus()).isEqualTo(429);
        assertThat(login("alice@example.com", PASSWORD, "192.0.2.46").getStatus()).isEqualTo(200);
    }

    @Test
    void aForgedForwardedForHeaderDoesNotEscapeTheLimit() throws Exception {
        accounts.active("alice@example.com", PASSWORD);
        // Real HTTP through Tomcat, so forwarded-header handling is the production one (S12).
        CookieManager browser = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(browser).build()) {
            client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).build(), HttpResponse.BodyHandlers.discarding());
            String token = browser.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals(RealCsrfToken.COOKIE))
                    .map(HttpCookie::getValue).findFirst().orElseThrow();
            int[] statuses = new int[6];
            for (int i = 0; i < 6; i++) {
                HttpRequest request = HttpRequest.newBuilder(uri(LOGIN))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header(RealCsrfToken.HEADER, token)
                        .header("X-Forwarded-For", "203.0.113." + i)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"alice@example.com\",\"password\":\"" + WRONG + "\"}"))
                        .build();
                statuses[i] = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            }
            assertThat(statuses).containsExactly(401, 401, 401, 401, 401, 429);
        }
    }

    private MockHttpServletResponse login(String email, String password, String clientIp) throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(csrf)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })).andReturn().getResponse();
    }

    private Integer attempts(String key) {
        return jdbcTemplate.queryForObject("SELECT attempt_count FROM auth_throttle WHERE throttle_key = ?", Integer.class, key);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

}
