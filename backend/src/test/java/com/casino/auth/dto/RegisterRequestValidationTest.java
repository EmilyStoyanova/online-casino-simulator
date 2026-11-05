package com.casino.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Bean Validation of {@link RegisterRequest}, including the {@link PasswordPolicy} (AC-1.2, AC-1.5). */
class RegisterRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final String PASSWORD = "correct-horse-battery";

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void aCompleteRequestIsValid() {
        assertThat(errors(request("alice@example.com", PASSWORD, "Alice", true))).isEmpty();
    }

    @Test
    void emailAndDisplayNameAreTrimmedButThePasswordIsNot() {
        RegisterRequest request = request("  Alice@Example.com\t", "  " + PASSWORD + "  ", "  Alice ", true);

        assertThat(request.email()).isEqualTo("Alice@Example.com");
        assertThat(request.displayName()).isEqualTo("Alice");
        assertThat(request.password()).isEqualTo("  " + PASSWORD + "  ");
        assertThat(errors(request)).isEmpty();
    }

    @Test
    void passwordLengthIsCountedInCharactersAndUtf8Bytes() {
        assertThat(errors(request("a@example.com", "a".repeat(11), "Alice", true)))
                .containsExactly("password: must be at least 12 characters long");
        assertThat(errors(request("a@example.com", "x7Kq-vN2r-Pz", "Alice", true))).isEmpty();
        // 12 emoji are 24 UTF-16 units but 12 characters, and 48 bytes.
        assertThat(errors(request("a@example.com", "😀".repeat(11), "Alice", true)))
                .containsExactly("password: must be at least 12 characters long");
        assertThat(errors(request("a@example.com", "😀".repeat(12), "Alice", true))).isEmpty();
        // 72 bytes is BCrypt's limit; longer is rejected, never truncated.
        assertThat(errors(request("a@example.com", "a".repeat(72), "Alice", true))).isEmpty();
        assertThat(errors(request("a@example.com", "a".repeat(73), "Alice", true)))
                .containsExactly("password: must be at most 72 bytes long in UTF-8");
        assertThat(errors(request("a@example.com", "ä".repeat(36), "Alice", true))).isEmpty();
        assertThat(errors(request("a@example.com", "ä".repeat(37), "Alice", true)))
                .containsExactly("password: must be at most 72 bytes long in UTF-8");
        assertThat(errors(request("a@example.com", "😀".repeat(19), "Alice", true)))
                .containsExactly("password: must be at most 72 bytes long in UTF-8");
    }

    @Test
    void thePasswordMustNotEqualTheEmailOrDisplayName() {
        assertThat(errors(request("alice.wonder@example.com", "Alice.Wonder@Example.com", "Alice", true)))
                .containsExactly("password: must not equal the email");
        assertThat(errors(request("alice@example.com", "alice wonderland", "Alice Wonderland", true)))
                .containsExactly("password: must not equal the display name");
        // Several broken rules are reported together.
        assertThat(errors(request("abc@example.com", "abc", "abc", true))).containsExactlyInAnyOrder(
                "password: must be at least 12 characters long", "password: must not equal the display name");
    }

    @Test
    void commonPasswordsAreRejectedIgnoringCaseAndSurroundingWhitespace() {
        for (String common : List.of("password1234", "Password1234", "1QAZ2WSX3EDC", "  qwertyuiopasdfghjkl\t")) {
            assertThat(errors(request("alice@example.com", common, "Alice", true))).as(common)
                    .containsExactly("password: must not be a commonly used password");
        }
        // Reported together with the other rules.
        assertThat(errors(request("iloveyou1234@example.com", "iloveyou1234@example.com", "Alice", true)))
                .containsExactly("password: must not equal the email");
        assertThat(errors(request("alice@example.com", "iloveyou1234", "iLoveYou1234", true))).containsExactlyInAnyOrder(
                "password: must not be a commonly used password", "password: must not equal the display name");
        // A variation that is not in the list is accepted.
        assertThat(errors(request("alice@example.com", "password1234-casino-zebra", "Alice", true))).isEmpty();
    }

    @Test
    void theEmailMustBeAWellFormedAsciiAddressOfAtMost254Characters() {
        for (String invalid : List.of("alice", "alice@", "@example.com", "alice example@example.com", "alïce@example.com",
                "alice@exämple.com")) {
            assertThat(errors(request(invalid, PASSWORD, "Alice", true))).as(invalid)
                    .containsExactly("email: must be a valid email address");
        }
        assertThat(errors(request("  ", PASSWORD, "Alice", true))).containsExactly("email: must not be blank");
        String label = "a".repeat(61) + ".";
        assertThat(errors(request("al@" + label.repeat(4) + "com", PASSWORD, "Alice", true))).isEmpty();
        assertThat(errors(request("alice@" + label.repeat(4) + "com", PASSWORD, "Alice", true)))
                .containsExactly("email: must be at most 254 characters long");
    }

    @Test
    void theDisplayNameHas3To30CharactersWithoutControlCharacters() {
        assertThat(errors(request("a@example.com", PASSWORD, "Ali", true))).isEmpty();
        assertThat(errors(request("a@example.com", PASSWORD, "A".repeat(30), true))).isEmpty();
        assertThat(errors(request("a@example.com", PASSWORD, "Al", true))).containsExactly("displayName: must be 3 to 30 characters long");
        assertThat(errors(request("a@example.com", PASSWORD, "   ", true)))
                .containsExactlyInAnyOrder("displayName: must not be blank", "displayName: must be 3 to 30 characters long");
        assertThat(errors(request("a@example.com", PASSWORD, "Ali\nce", true)))
                .containsExactly("displayName: must not contain control characters");
        assertThat(errors(request("a@example.com", PASSWORD, "Zoë 🎲", true))).isEmpty();
    }

    @Test
    void adultConfirmationMustBeExplicitlyTrue() {
        assertThat(errors(request("a@example.com", PASSWORD, "Alice", false))).containsExactly("adultConfirmed: must be true");
        assertThat(errors(new RegisterRequest("a@example.com", PASSWORD, "Alice", null))).containsExactly("adultConfirmed: must be true");
    }

    @Test
    void everyPropertyIsRequired() {
        assertThat(errors(new RegisterRequest(null, null, null, null))).containsExactlyInAnyOrder(
                "email: must not be blank", "password: must not be null", "displayName: must not be blank",
                "adultConfirmed: must be true");
    }

    @Test
    void toStringNeverShowsThePassword() {
        assertThat(request("alice@example.com", PASSWORD, "Alice", true).toString()).doesNotContain(PASSWORD);
    }

    private static RegisterRequest request(String email, String password, String displayName, boolean adultConfirmed) {
        return new RegisterRequest(email, password, displayName, adultConfirmed);
    }

    private static List<String> errors(RegisterRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
    }
}
