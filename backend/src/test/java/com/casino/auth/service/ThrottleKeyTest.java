package com.casino.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.casino.auth.config.ThrottleProperties;
import com.casino.auth.config.ThrottleProperties.Limit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** {@link ThrottleKey}s created by {@link ThrottleKeys} (HMAC-SHA-256) and the validation of the secret. */
class ThrottleKeyTest {

    private static final String EMAIL = "alice@example.com";
    private static final String IP = "203.0.113.7";
    private static final String SECRET = randomSecret(32);

    private final ThrottleKeys keys = keys(SECRET);

    @Test
    void keysAreTheScopeAndAnHmacThatFitTheColumn() {
        for (ThrottleKey key : allKeys(keys)) {
            // Same pattern as ck_auth_throttle_key_hashed; VARCHAR(90).
            assertThat(key.value()).matches("^[A-Z][A-Z_]*:[0-9a-f]{64}$").startsWith(key.scope() + ":").hasSizeLessThanOrEqualTo(90);
            assertThat(key).hasToString("ThrottleKey[" + key.scope() + "]");
        }
    }

    @Test
    void theHashIsAnHmacOfScopeAndSubjectWithTheSecret() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(("LOGIN_IP\0" + IP).getBytes(StandardCharsets.UTF_8)));
        String plainSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(IP.getBytes(StandardCharsets.UTF_8)));

        assertThat(keys.loginIp(IP).value()).isEqualTo("LOGIN_IP:" + expected).doesNotContain(plainSha256);
    }

    @Test
    void theSameSecretAlwaysGivesTheSameKeys() {
        // E.g. after a restart or on another instance.
        assertThat(allKeys(keys(SECRET))).isEqualTo(allKeys(keys));
    }

    @Test
    void aDifferentSecretGivesDifferentKeys() {
        List<String> other = values(allKeys(keys(randomSecret(32))));

        assertThat(values(allKeys(keys))).doesNotContainAnyElementsOf(other);
    }

    @Test
    void keysNeverContainTheEmailOrIpAddress() {
        for (ThrottleKey key : allKeys(keys)) {
            // Only hex digits after the scope, so no letter of the email and no dot of the IP can occur.
            assertThat(key.value()).doesNotContain("alice", "example", IP);
            assertThat(key.toString()).doesNotContain("alice", IP);
        }
    }

    @Test
    void theEmailIsNormalised() {
        assertThat(keys.loginAccountIp(" Alice@Example.COM\t", IP)).isEqualTo(keys.loginAccountIp(EMAIL, IP));
    }

    @Test
    void differentSubjectsOrScopesGiveDifferentKeys() {
        List<ThrottleKey> distinct = List.of(
                keys.loginAccountIp(EMAIL, IP),
                keys.loginAccountIp(EMAIL, "203.0.113.8"),
                keys.loginAccountIp("bob@example.com", IP),
                // The parts are separated, so their boundary cannot be shifted.
                keys.loginAccountIp("a@b", "c1"),
                keys.loginAccountIp("a@bc", "1"),
                keys.loginIp(IP),
                keys.registerIp(IP));

        // The hash part alone differs as well: the scope is part of the HMAC message.
        assertThat(distinct).extracting(key -> key.value().substring(key.value().indexOf(':') + 1)).doesNotHaveDuplicates();
    }

    @Test
    void subjectsAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> keys.loginAccountIp(null, IP)).withMessage("email");
        assertThatNullPointerException().isThrownBy(() -> keys.loginAccountIp(EMAIL, null)).withMessage("clientIp");
        assertThatNullPointerException().isThrownBy(() -> keys.loginIp(null)).withMessage("clientIp");
        assertThatNullPointerException().isThrownBy(() -> keys.registerIp(null)).withMessage("clientIp");
    }

    @Test
    void aMissingSecretIsRejected() {
        for (String missing : new String[] {null, "", "   "}) {
            assertThatIllegalStateException().isThrownBy(() -> new ThrottleKeys(properties(missing, false)))
                    .withMessageStartingWith("Missing throttling secret")
                    .withMessageContaining("APP_SECURITY_THROTTLE_SECRET");
        }
    }

    @Test
    void anInvalidSecretIsRejectedWithoutRevealingIt() {
        String notBase64 = "change-me-local-only";
        String tooShort = randomSecret(31);

        assertThatIllegalStateException().isThrownBy(() -> new ThrottleKeys(properties(notBase64, false)))
                .withMessageStartingWith("Invalid throttling secret: not Base64")
                .withMessageNotContaining(notBase64);
        assertThatIllegalStateException().isThrownBy(() -> new ThrottleKeys(properties(tooShort, false)))
                .withMessageStartingWith("Invalid throttling secret: only 31 bytes")
                .withMessageNotContaining(tooShort);
        // Even if tests allow an ephemeral secret, a configured but invalid one is an error.
        assertThatIllegalStateException().isThrownBy(() -> new ThrottleKeys(properties(tooShort, true)));
    }

    @Test
    void anEphemeralSecretIsRandomAndOnlyUsedWithoutAConfiguredOne() {
        assertThat(values(allKeys(new ThrottleKeys(properties(null, true)))))
                .doesNotContainAnyElementsOf(values(allKeys(new ThrottleKeys(properties(null, true)))));
        // A configured secret takes precedence.
        assertThat(allKeys(new ThrottleKeys(properties(SECRET, true)))).isEqualTo(allKeys(keys));
    }

    @Test
    void theApplicationDoesNotStartWithoutAValidSecret() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(KeysConfiguration.class)
                .withPropertyValues("app.security.throttle.login-account-ip.max-attempts=5",
                        "app.security.throttle.login-account-ip.window=15m",
                        "app.security.throttle.login-ip.max-attempts=50", "app.security.throttle.login-ip.window=15m",
                        "app.security.throttle.register-ip.max-attempts=10", "app.security.throttle.register-ip.window=1h");

        runner.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageStartingWith("Missing throttling secret"));
        runner.withPropertyValues("app.security.throttle.secret=" + randomSecret(16))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageStartingWith("Invalid throttling secret"));
        runner.withPropertyValues("app.security.throttle.secret=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ThrottleKeys.class));
    }

    @Test
    void propertiesNeverShowTheSecret() {
        assertThat(properties(SECRET, false).toString()).doesNotContain(SECRET).contains("secret=<redacted>");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ThrottleProperties.class)
    @Import(ThrottleKeys.class)
    static class KeysConfiguration {
    }

    private static List<ThrottleKey> allKeys(ThrottleKeys keys) {
        return List.of(keys.loginAccountIp(EMAIL, IP), keys.loginIp(IP), keys.registerIp(IP));
    }

    private static List<String> values(List<ThrottleKey> keys) {
        return keys.stream().map(ThrottleKey::value).toList();
    }

    private static ThrottleKeys keys(String secret) {
        return new ThrottleKeys(properties(secret, false));
    }

    private static ThrottleProperties properties(String secret, boolean ephemeralSecret) {
        Limit limit = new Limit(5, Duration.ofMinutes(15));
        return new ThrottleProperties(limit, limit, limit, secret, ephemeralSecret);
    }

    /** Generated per test run: no secret is stored in the repository. */
    private static String randomSecret(int bytes) {
        byte[] secret = new byte[bytes];
        new SecureRandom().nextBytes(secret);
        return Base64.getEncoder().encodeToString(secret);
    }
}
