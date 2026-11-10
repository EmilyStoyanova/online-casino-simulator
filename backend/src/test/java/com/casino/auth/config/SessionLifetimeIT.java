package com.casino.auth.config;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static com.casino.SessionCookies.sessionId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.auth.service.MutableClock;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Session lifetime (AC-5.2, AC-5.3) with Spring Session JDBC: the 30-minute inactivity timeout (Spring
 * Session's own clock; the stored last access is moved back) and the 12-hour absolute lifetime (the
 * application {@link MutableClock} is moved forward). Both end with {@code 401 SESSION_EXPIRED}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SessionLifetimeIT.ClockConfiguration.class})
class SessionLifetimeIT {

    private static final String PASSWORD = "correct-horse-battery-3e8f";
    private static final String PROTECTED = "/api/v1/wallet";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MutableClock clock;

    private TestAccounts accounts;
    private RealCsrfToken csrf;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        accounts.active("alice@example.com", PASSWORD);
        csrf = RealCsrfToken.fetch(mockMvc);
    }

    @AfterEach
    void deleteRows() {
        testDatabase.reset();
    }

    @Test
    void aSessionEndsAfter30MinutesOfInactivity() throws Exception {
        String setCookie = login();
        String sessionId = sessionId(setCookie);
        assertThat(jdbcTemplate.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(30 * 60);

        // The last request was 30 minutes and 1 second ago.
        jdbcTemplate.update("""
                UPDATE spring_session SET last_access_time = last_access_time - ?, expiry_time = expiry_time - ?
                WHERE session_id = ?""", 1_801_000L, 1_801_000L, sessionId);

        expectSessionExpired(perform(get(PROTECTED).cookie(cookie(setCookie))));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
    }

    @Test
    void activityKeepsASessionWithinTheInactivityTimeout() throws Exception {
        String setCookie = login();
        jdbcTemplate.update("UPDATE spring_session SET last_access_time = last_access_time - ? WHERE session_id = ?",
                29 * 60_000L, sessionId(setCookie));

        assertThat(perform(get(PROTECTED).cookie(cookie(setCookie))).getStatus()).isEqualTo(404);
        // The request renewed the last access.
        assertThat(jdbcTemplate.queryForObject("SELECT last_access_time FROM spring_session WHERE session_id = ?",
                Long.class, sessionId(setCookie))).isGreaterThan(System.currentTimeMillis() - 60_000L);
    }

    @Test
    void aSessionEndsTwelveHoursAfterSignInHoweverActiveItIs() throws Exception {
        Cookie session = cookie(login());

        // Active all the time (Spring Session's idle clock is real time), only the application clock moves.
        clock.advance(Duration.ofHours(12).minusSeconds(1));
        assertThat(perform(get(PROTECTED).cookie(session)).getStatus()).isEqualTo(404);

        clock.advance(Duration.ofSeconds(1));
        expectSessionExpired(perform(get(PROTECTED).cookie(session)));
        // Invalidated: the row is deleted, and the cookie stays useless afterwards.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
        expectSessionExpired(perform(get(PROTECTED).cookie(session)));
    }

    @Test
    void anExpiredSessionOnAPublicOperationContinuesAnonymously() throws Exception {
        Cookie session = cookie(login());
        clock.advance(Duration.ofHours(13));

        // E.g. the SPA fetches a token, then signs in again.
        MockHttpServletResponse token = perform(get("/api/v1/auth/csrf").cookie(session));
        assertThat(token.getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
        String renewed = login();
        assertThat(perform(get(PROTECTED).cookie(cookie(renewed))).getStatus()).isEqualTo(404);
    }

    private String login() throws Exception {
        MockHttpServletResponse response = perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\",\"password\":\"" + PASSWORD + "\"}").with(csrf));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return sessionCookie(response);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static void expectSessionExpired(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body).isEqualTo(401);
        assertThat(Json31.mapper().readTree(body).path("code").asText()).isEqualTo("SESSION_EXPIRED");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        /** Replaces the application clock (filter, services); Spring Session keeps real time. */
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.now());
        }
    }
}
