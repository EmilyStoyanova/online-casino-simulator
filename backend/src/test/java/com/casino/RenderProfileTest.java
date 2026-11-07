package com.casino;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.casino.auth.config.RegistrationProperties;
import com.casino.common.config.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The {@code render} profile ({@code application-render.yml}) for the public deployment: only the
 * configuration is resolved, in an empty context without a database. Values that Render or the
 * operator provide are passed as command-line arguments.
 */
class RenderProfileTest {

    @Test
    void behindRendersProxyEverythingIsHttpsOnlyAndNoForwardedHeaderIsTrusted() {
        try (ConfigurableApplicationContext context = start(true, "--PORT=10000")) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("server.port")).isEqualTo("10000");
            assertThat(env.getProperty("app.security.https-only")).isEqualTo("true");
            assertThat(env.getProperty("server.servlet.session.cookie.name")).isEqualTo("__Host-SESSION");
            assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
            assertThat(env.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(env.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
            assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
        }
        // Read from the file itself: a developer's .env (imported in tests) may set the strategy.
        assertThatProfileFile("server.forward-headers-strategy", "none");
        assertThatProfileFile("server.tomcat.remoteip.internal-proxies", null);
        assertThatProfileFile("server.tomcat.remoteip.trusted-proxies", null);
    }

    @Test
    void noBrowserOriginIsAllowedUntilOneIsConfigured() throws Exception {
        // The profile's default is empty, not the local http://localhost:5173. Read from the file
        // itself: a developer's .env (imported in tests) may set the variable.
        List<PropertySource<?>> profile = new YamlPropertySourceLoader()
                .load("render", new ClassPathResource("application-render.yml"));
        assertThat(profile.getFirst().getProperty("app.cors.allowed-origins")).hasToString("${APP_CORS_ALLOWED_ORIGINS:}");

        try (ConfigurableApplicationContext context = start(true, "--APP_CORS_ALLOWED_ORIGINS=")) {
            assertThat(corsOrigins(context)).isEmpty();
        }
        try (ConfigurableApplicationContext context = start(true, "--APP_CORS_ALLOWED_ORIGINS=https://casino.example.org")) {
            assertThat(corsOrigins(context)).containsExactly("https://casino.example.org");
        }
    }

    @Test
    void registrationIsClosedOnRenderUnlessOpenedOnPurposeAndOpenLocally() throws Exception {
        // Defaults read from the files: a developer's .env (imported in tests) may set the variable.
        assertThat(load("application-render.yml").getProperty("app.registration.enabled"))
                .hasToString("${APP_REGISTRATION_ENABLED:false}");
        assertThat(load("application.yml").getProperty("app.registration.enabled"))
                .hasToString("${APP_REGISTRATION_ENABLED:true}");

        for (String[] variant : new String[][] {{"--APP_REGISTRATION_ENABLED=false", "false"},
                {"--APP_REGISTRATION_ENABLED=true", "true"}}) {
            try (ConfigurableApplicationContext context = start(true, variant[0])) {
                assertThat(Binder.get(context.getEnvironment()).bind("app.registration", RegistrationProperties.class)
                        .map(RegistrationProperties::enabled).get()).hasToString(variant[1]);
            }
        }
    }

    @Test
    void withoutTheProfileTheLocalDefaultsAreUnchanged() {
        try (ConfigurableApplicationContext context = start(false, "--PORT=10000")) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("server.port")).isNotEqualTo("10000");
            assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("none");
            assertThat(env.getProperty("server.servlet.session.cookie.name")).isEqualTo("SESSION");
            assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
            assertThat(env.getProperty("springdoc.swagger-ui.enabled")).isNull();
            // Local HTTP: HSTS and Secure CSRF cookies only for secure requests.
            assertThat(Binder.get(env).bind("app.security.https-only", Boolean.class).orElse(false)).isFalse();
        }
    }

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).getFirst();
    }

    private static void assertThatProfileFile(String property, String expected) {
        try {
            Object value = load("application-render.yml").getProperty(property);
            assertThat(value == null ? null : value.toString()).as(property).isEqualTo(expected);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> corsOrigins(ConfigurableApplicationContext context) {
        return Binder.get(context.getEnvironment()).bind("app.cors", CorsProperties.class)
                .map(CorsProperties::allowedOrigins).orElse(List.of());
    }

    private static ConfigurableApplicationContext start(boolean render, String... args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false);
        if (render) {
            builder.profiles("render");
        }
        return builder.run(args);
    }

    /** No beans and no auto-configuration: only the configuration files are processed. */
    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
