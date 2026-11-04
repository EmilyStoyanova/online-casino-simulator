package com.casino.auth.dto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Common-password denylist of the {@link PasswordPolicy} (W2-12, week-02.md §7.3): the passwords that
 * occur more than once in Mark Burnett's ten million password dataset (public domain), limited to those
 * the other rules would accept (12 to 72 printable ASCII characters), trimmed and lower-cased.
 * Source, licence and regeneration: {@code common-passwords-NOTICE.md} next to the list.
 */
final class CommonPasswords {

    static final String RESOURCE = "/auth/common-passwords.txt";

    private CommonPasswords() {
    }

    /** Ignores case and surrounding whitespace, like the other comparisons of the policy. */
    static boolean contains(String password) {
        return Holder.PASSWORDS.contains(password.strip().toLowerCase(Locale.ROOT));
    }

    static Set<String> entries() {
        return Holder.PASSWORDS;
    }

    /** Loaded once, on first use. A missing or empty list fails instead of silently weakening the policy. */
    private static final class Holder {
        static final Set<String> PASSWORDS = load();
    }

    private static Set<String> load() {
        InputStream stream = CommonPasswords.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("The common-password list " + RESOURCE + " is missing");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8))) {
            Set<String> passwords = reader.lines().filter(line -> !line.isEmpty()).collect(Collectors.toUnmodifiableSet());
            if (passwords.isEmpty()) {
                throw new IllegalStateException("The common-password list " + RESOURCE + " is empty");
            }
            return passwords;
        } catch (IOException ex) {
            throw new UncheckedIOException("The common-password list " + RESOURCE + " cannot be read", ex);
        }
    }
}
