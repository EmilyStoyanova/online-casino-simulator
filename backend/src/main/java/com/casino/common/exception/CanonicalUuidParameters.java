package com.casino.common.exception;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * UUID request values (parameters and headers, e.g. {@code Idempotency-Key}) accept the canonical form only
 * (Q18): 36 characters, {@code 8-4-4-4-12} hexadecimal digits, either case. {@code UUID.fromString} alone
 * also accepts shortened forms such as {@code 1-1-1-1-1}, which would make two spellings the same key.
 *
 * <p>Anything else is a type mismatch: {@code 400 MALFORMED_REQUEST}, the convention for unconvertible
 * values ({@code docs/api.md}). A valid value is parsed into a {@link UUID}, so upper- and lower-case
 * spellings of one key are equal. Works like {@link DecimalIntegerParameters} and lives here for the same
 * reason (ArchUnit R5).
 */
@ControllerAdvice
class CanonicalUuidParameters {

    private static final Pattern CANONICAL =
            Pattern.compile("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$");

    @InitBinder
    void acceptCanonicalUuidsOnly(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new CanonicalUuidEditor());
    }

    private static final class CanonicalUuidEditor extends PropertyEditorSupport {

        @Override
        public void setAsText(String text) {
            if (text == null || !CANONICAL.matcher(text).matches()) {
                throw new IllegalArgumentException("Not a canonical UUID");
            }
            setValue(UUID.fromString(text));
        }
    }
}
