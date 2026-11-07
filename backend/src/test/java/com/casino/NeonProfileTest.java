package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The {@code neon} profile ({@code application-neon.yml}): only the configuration is resolved, in an
 * empty context without a database. Values are passed as command-line arguments, which take
 * precedence over a developer's {@code .env.neon}, so its real values are never read here.
 */
class NeonProfileTest {

    private static final String[] NEON = {"--NEON_DB_HOST=ep-example-123456.eu-central-1.aws.neon.tech",
            "--NEON_DB_NAME=neondb", "--NEON_DB_USERNAME=app_user", "--NEON_DB_PASSWORD=example-only"};

    @Test
    void theProfileConnectsToNeonWithVerifiedTlsAndChannelBinding() throws Exception {
        try (ConfigurableApplicationContext context = start(true, NEON)) {
            Environment env = context.getEnvironment();
            String url = env.getProperty("spring.datasource.url");

            assertThat(url).isEqualTo("jdbc:postgresql://ep-example-123456.eu-central-1.aws.neon.tech/neondb"
                    + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require");
            assertThat(url).doesNotContain("example-only", "app_user");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("app_user");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("example-only");
            assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("15000");

            // The driver reads the parameters as intended (URL values win over driver properties).
            Properties parsed = Driver.parseURL(url, new Properties());
            assertThat(parsed).containsEntry("sslmode", "verify-full")
                    .containsEntry("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory")
                    .containsEntry("channelBinding", "require")
                    .containsEntry("PGDBNAME", "neondb");
            // Flyway and Spring Session use the same, direct connection.
            assertThat(env.getProperty("spring.flyway.url")).isNull();
            assertThat(env.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        }
    }

    @Test
    void withoutTheProfileTheLocalConfigurationIsUnchanged() {
        try (ConfigurableApplicationContext context = start(false, NEON)) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("spring.datasource.url")).doesNotContain("neon.tech", "verify-full");
            assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
        }
    }

    @Test
    void withoutTheNeonValuesTheProfileNeverFallsBackToTheLocalDatabase() {
        // A developer's .env.neon would supply the values; nothing to check then.
        assumeFalse(Files.exists(Path.of(".env.neon")) || Files.exists(Path.of("../.env.neon")));

        try (ConfigurableApplicationContext context = start(true)) {
            // The data source binding keeps an unresolved placeholder, so the connection fails with
            // "UnknownHostException: ${NEON_DB_HOST}" instead of reaching any other database.
            String bound = Binder.get(context.getEnvironment()).bind("spring.datasource.url", String.class).get();
            assertThat(bound).startsWith("jdbc:postgresql://${NEON_DB_HOST}/${NEON_DB_NAME}?sslmode=verify-full");
            assertThat(Binder.get(context.getEnvironment()).bind("spring.datasource.password", String.class).get())
                    .isEqualTo("${NEON_DB_PASSWORD}");
        }
    }

    private static ConfigurableApplicationContext start(boolean neon, String... args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false);
        if (neon) {
            builder.profiles("neon");
        }
        return builder.run(args);
    }

    /** No beans and no auto-configuration: only the configuration files are processed. */
    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
