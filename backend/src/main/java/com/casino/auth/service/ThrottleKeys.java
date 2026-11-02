package com.casino.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.casino.auth.config.ThrottleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates the {@link ThrottleKey}s: {@code <scope>:<HMAC-SHA-256 hex>} of the scope and the normalised
 * email and/or client IP, keyed with {@code app.security.throttle.secret}. Without the secret the
 * stored keys cannot be traced back to an email or IP address, not even by trying every IPv4 address.
 *
 * <p>The secret is required and checked at start-up (Base64, at least 32 bytes): the application does
 * not start without a valid one. Only test configurations may set {@code ephemeral-secret} to use a
 * random key per start instead.
 *
 * <p>The email is normalised here (trimmed, lower case) and not only by the account service: case
 * variants of the same email, and unknown emails, must share one counter.
 */
@Component
public class ThrottleKeys {

    static final int MIN_SECRET_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SECRET_HINT = "app.security.throttle.secret (APP_SECURITY_THROTTLE_SECRET) must be a "
            + "Base64-encoded random value of at least " + MIN_SECRET_BYTES + " bytes, e.g. `openssl rand -base64 32`";
    private static final HexFormat HEX = HexFormat.of();
    private static final Logger log = LoggerFactory.getLogger(ThrottleKeys.class);

    private final SecretKeySpec secret;

    public ThrottleKeys(ThrottleProperties properties) {
        this.secret = new SecretKeySpec(secretBytes(properties), ALGORITHM);
        // Fails at start-up rather than on the first login if the platform lacks the algorithm.
        hmacHex(ThrottleScope.LOGIN_IP, "");
    }

    /** Failed logins for {@code email} from {@code clientIp}. */
    public ThrottleKey loginAccountIp(String email, String clientIp) {
        return key(ThrottleScope.LOGIN_ACCOUNT_IP, normalizeEmail(email), requireIp(clientIp));
    }

    /** Failed logins from {@code clientIp}. */
    public ThrottleKey loginIp(String clientIp) {
        return key(ThrottleScope.LOGIN_IP, requireIp(clientIp));
    }

    /** Registration attempts from {@code clientIp}. */
    public ThrottleKey registerIp(String clientIp) {
        return key(ThrottleScope.REGISTER_IP, requireIp(clientIp));
    }

    private ThrottleKey key(ThrottleScope scope, String... parts) {
        return new ThrottleKey(scope, hmacHex(scope, parts));
    }

    /**
     * The scope is part of the message, so the same IP gives unrelated keys in different scopes. The parts
     * are separated by NUL, which occurs in neither emails nor IP addresses.
     */
    private String hmacHex(ThrottleScope scope, String... parts) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secret);
            String message = scope.name() + '\0' + String.join("\0", parts);
            return HEX.formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // Every Java platform must support HmacSHA256.
            throw new IllegalStateException(e);
        }
    }

    /** Never includes the configured value in an error message or log. */
    private static byte[] secretBytes(ThrottleProperties properties) {
        String configured = properties.secret();
        if (configured != null && !configured.isBlank()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(configured.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid throttling secret: not Base64. " + SECRET_HINT);
            }
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("Invalid throttling secret: only " + bytes.length + " bytes. " + SECRET_HINT);
            }
            return bytes;
        }
        if (properties.ephemeralSecret()) {
            log.warn("Throttling uses a random secret for this start (app.security.throttle.ephemeral-secret=true); "
                    + "counters are lost on restart and differ between instances. Tests only.");
            byte[] bytes = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(bytes);
            return bytes;
        }
        throw new IllegalStateException("Missing throttling secret. " + SECRET_HINT);
    }

    private static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
    }

    private static String requireIp(String clientIp) {
        return Objects.requireNonNull(clientIp, "clientIp");
    }
}
