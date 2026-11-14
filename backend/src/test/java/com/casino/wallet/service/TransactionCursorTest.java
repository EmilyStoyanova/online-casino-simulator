package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.casino.common.exception.FieldViolation;
import com.casino.common.exception.ProblemCode;
import com.casino.wallet.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;

/** {@link TransactionCursor}: opaque, URL-safe, within the contract's 64 characters, and strict. */
class TransactionCursorTest {

    @Test
    void aCursorRoundTripsAndUsesOnlyTheContractAlphabet() {
        for (long id : new long[] {1, 7, 1000, 9_007_199_254_740_991L, Long.MAX_VALUE}) {
            String cursor = TransactionCursor.after(id);

            assertThat(cursor).matches("^[A-Za-z0-9_-]+$").hasSizeLessThanOrEqualTo(30);
            assertThat(TransactionCursor.decode(cursor)).isEqualTo(id);
        }
        // Opaque, but deterministic: the same position always has the same cursor.
        assertThat(TransactionCursor.after(42)).isEqualTo(TransactionCursor.after(42)).isEqualTo(encode("v1:42"));
    }

    @Test
    void onlyPositiveIdsHaveACursor() {
        assertThatIllegalArgumentException().isThrownBy(() -> TransactionCursor.after(0));
        assertThatIllegalArgumentException().isThrownBy(() -> TransactionCursor.after(-1));
    }

    @Test
    void anythingButAnIssuedCursorIsRejectedAsAValidationError() {
        String[] invalid = {
                "",                                   // empty (minLength 1)
                "a".repeat(65),                       // longer than 64
                "djE6NDI=",                           // padding: not the issued form
                "djE6NDI+",                           // standard Base64 alphabet
                "v1:42",                              // not encoded
                "!!!",                                // characters outside the alphabet
                "A",                                  // not decodable Base64
                encode("42"),                         // no version
                encode("v2:42"),                      // unknown version
                encode("v1:0"),                       // not positive
                encode("v1:-5"),                      // negative
                encode("v1:042"),                     // leading zero
                encode("v1:4 2"),                     // not a number
                encode("v1:9223372036854775808"),     // above Long.MAX_VALUE
                encode("v1:42\n"),                    // trailing data
                TransactionCursor.after(42) + "A",    // altered
                "djE6NDJ",                            // non-canonical last character (same bytes as "djE6NDI")
        };
        for (String cursor : invalid) {
            assertThatThrownBy(() -> TransactionCursor.decode(cursor)).as(cursor)
                    .isInstanceOfSatisfying(InvalidCursorException.class, error -> {
                        assertThat(error.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                        // The rejected value is never echoed.
                        assertThat(error.violations()).containsExactly(new FieldViolation("cursor", "is not a valid cursor"));
                    });
        }
        assertThatThrownBy(() -> TransactionCursor.decode(null)).isInstanceOf(InvalidCursorException.class);
    }

    private static String encode(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }
}
