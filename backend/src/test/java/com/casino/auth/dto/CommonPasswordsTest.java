package com.casino.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/** The bundled common-password list of the {@link PasswordPolicy} (W2-12). */
class CommonPasswordsTest {

    @Test
    void theListIsBundledWithItsNotice() {
        assertThat(CommonPasswords.entries()).hasSize(15_535).contains("password1234", "1qaz2wsx3edc", "qwertyuiopasdfghjkl");
        // MIT License of SecLists, the source of the copy.
        assertThat(CommonPasswords.class.getResource("/auth/common-passwords-NOTICE.md")).isNotNull();
    }

    @Test
    void everyEntryIsOneThePolicyWouldOtherwiseAccept() {
        // Shorter or longer entries could never match; upper case or surrounding whitespace never would.
        assertThat(CommonPasswords.entries()).allSatisfy(entry -> {
            assertThat(entry).matches("[\\x20-\\x7e]{12,72}");
            assertThat(entry).isEqualTo(entry.strip().toLowerCase(Locale.ROOT));
        });
    }

    @Test
    void lookupsIgnoreCaseAndSurroundingWhitespace() {
        assertThat(CommonPasswords.contains("PassWord1234")).isTrue();
        assertThat(CommonPasswords.contains(" password1234\n")).isTrue();
        assertThat(CommonPasswords.contains("pass word1234")).isFalse();
        assertThat(CommonPasswords.contains("correct-horse-battery")).isFalse();
    }
}
