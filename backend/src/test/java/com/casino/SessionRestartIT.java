package com.casino;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionId;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.casino.auth.service.MutableClock;
import com.casino.auth.service.SessionSignInService;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-2.3: a signed-in session survives a restart of the application, because it is stored in
 * PostgreSQL (Spring Session JDBC). Two application instances run one after the other against the
 * same database and configuration, and the second one accepts the cookie that the first one issued.
 * The session keeps the sign-in time of the original login, so a restart does not extend the 12-hour
 * absolute lifetime.
 *
 * <p>The instances are started with {@link SpringApplicationBuilder}, not by the test framework, so
 * that the test can stop the first one. They contain only the production components: the test
 * configurations under {@code com.casino} (Testcontainers, failing filters, clocks) are excluded from
 * component scanning.
 */
class SessionRestartIT {

    private static final String PASSWORD = "correct-horse-battery-3e8f";
    private static final String PROTECTED = "/api/v1/wallet";
    private static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);

    /** Outlives both application instances; stopped only after the test. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void aSignedInSessionSurvivesARestartWithItsOriginalSignInTime() throws Exception {
        JdbcTemplate database = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        String[] configuration = configuration();
        Instant signedInAt = Instant.parse("2026-11-03T08:00:00Z");

        try (HttpClient client = HttpClient.newHttpClient()) {
            UUID userId;
            String setCookie;
            String primaryId;
            byte[] storedSignInTime;
            try (Instance first = Instance.start(client, new MutableClock(signedInAt), configuration)) {
                userId = new TestAccounts(database, first.context().getBean(PasswordEncoder.class)).active("alice@example.com", PASSWORD);
                setCookie = first.login("alice@example.com", PASSWORD);
                assertThat(first.get(PROTECTED, setCookie).statusCode()).isEqualTo(404);

                primaryId = primaryId(database, setCookie);
                storedSignInTime = signInTime(database, setCookie);
                assertThat(deserialize(storedSignInTime)).isEqualTo(signedInAt);
            }

            // Stopped; the session stays in the database.
            assertThat(principal(database, setCookie)).isEqualTo(userId.toString());

            // 12 hours after sign-in minus 1 second: still within the absolute lifetime of the original login.
            MutableClock clock = new MutableClock(signedInAt.plus(ABSOLUTE_TIMEOUT).minusSeconds(1));
            try (Instance second = Instance.start(client, clock, configuration)) {
                // Without the cookie, the protected path requires sign-in ...
                expectProblem(second.get(PROTECTED, null), 401, "AUTHENTICATION_REQUIRED");
                // ... with the original cookie the user is authenticated (unknown path: 404, not 401).
                HttpResponse<String> resumed = second.get(PROTECTED, setCookie);
                assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(404);
                // Same session (no new cookie, same row and principal) and the original sign-in time.
                assertThat(resumed.headers().allValues(HttpHeaders.SET_COOKIE)).noneMatch(header -> header.startsWith("SESSION="));
                assertThat(primaryId(database, setCookie)).isEqualTo(primaryId);
                assertThat(principal(database, setCookie)).isEqualTo(userId.toString());
                assertThat(signInTime(database, setCookie)).isEqualTo(storedSignInTime);

                // The lifetime counts from the original sign-in, not from the restart.
                clock.advance(Duration.ofSeconds(1));
                expectProblem(second.get(PROTECTED, setCookie), 401, "SESSION_EXPIRED");
                assertThat(database.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
            }
        }
    }

    /** Identical for both instances. The throttling secret is random per test run. */
    private static String[] configuration() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new String[] {
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--app.security.throttle.secret=" + Base64.getEncoder().encodeToString(secret),
                "--app.security.session.absolute-timeout=" + ABSOLUTE_TIMEOUT,
        };
    }

    private static String primaryId(JdbcTemplate database, String setCookie) {
        return database.queryForObject("SELECT primary_id FROM spring_session WHERE session_id = ?", String.class,
                sessionId(setCookie));
    }

    private static String principal(JdbcTemplate database, String setCookie) {
        return database.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?", String.class,
                sessionId(setCookie));
    }

    private static byte[] signInTime(JdbcTemplate database, String setCookie) {
        return database.queryForObject("""
                SELECT a.attribute_bytes FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id = a.session_primary_id
                WHERE s.session_id = ? AND a.attribute_name = ?""", byte[].class, sessionId(setCookie),
                SessionSignInService.AUTHENTICATED_AT);
    }

    /** Spring Session JDBC stores attribute values with Java serialization. */
    private static Instant deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (Instant) in.readObject();
        }
    }

    private static void expectProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(Json31.mapper().readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    /** One running application instance, reached over real HTTP. */
    private record Instance(ConfigurableApplicationContext context, HttpClient client, URI base) implements AutoCloseable {

        static Instance start(HttpClient client, Clock clock, String... configuration) {
            ApplicationContextInitializer<GenericApplicationContext> testSetup = context -> {
                context.getBeanFactory().registerSingleton("productionComponentsOnly", new ProductionComponentsOnly());
                // Replaces the application clock (TimeConfig); Spring Session's inactivity clock keeps real time.
                context.registerBean("restartTestClock", Clock.class, () -> clock, definition -> definition.setPrimary(true));
            };
            ConfigurableApplicationContext context = new SpringApplicationBuilder(CasinoSimulatorApplication.class)
                    .initializers(testSetup)
                    .registerShutdownHook(false)
                    .run(configuration);
            assertThat(context.getBeanNamesForType(PostgreSQLContainer.class)).isEmpty();
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            return new Instance(context, client, URI.create("http://localhost:" + port));
        }

        /** Signs in like the SPA (token from the CSRF endpoint) and returns the session's {@code Set-Cookie}. */
        String login(String email, String password) throws Exception {
            HttpResponse<String> token = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/csrf")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(token.statusCode()).isEqualTo(204);
            String xsrf = cookie(setCookie(token, "XSRF-TOKEN")).getValue();

            HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + xsrf)
                    .header("X-XSRF-TOKEN", xsrf)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return setCookie(response, "SESSION");
        }

        /** A GET that sends the cookie of the given {@code Set-Cookie} header, if any. */
        HttpResponse<String> get(String path, String setCookie) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
            if (setCookie != null) {
                request.header(HttpHeaders.COOKIE, "SESSION=" + cookie(setCookie).getValue());
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            context.close();
        }

        private static String setCookie(HttpResponse<String> response, String name) {
            return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
                    .filter(header -> header.startsWith(name + "=") && !header.startsWith(name + "=;"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No " + name + " cookie"));
        }
    }

    /** Excludes every class compiled from the test sources from component scanning. */
    private static final class ProductionComponentsOnly extends TypeExcludeFilter {

        private static final Path TEST_CLASSES = testClasses();

        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) throws IOException {
            Resource resource = metadataReader.getResource();
            return resource.isFile() && resource.getFile().toPath().toAbsolutePath().startsWith(TEST_CLASSES);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ProductionComponentsOnly;
        }

        @Override
        public int hashCode() {
            return ProductionComponentsOnly.class.hashCode();
        }

        private static Path testClasses() {
            try {
                return Path.of(SessionRestartIT.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
