package com.casino.common.exception;

import java.beans.PropertyEditorSupport;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Integer request parameters ({@code int}/{@code Integer}, {@code long}/{@code Long}) accept decimal
 * digits only, with an optional leading minus ({@code ^-?[0-9]+$}), as an OpenAPI {@code integer} is
 * written. Spring's default number conversion would also read {@code 0x10}, {@code #10}, {@code +5} or
 * padded values: a failed conversion falls back to its {@code CustomNumberEditor}, which decodes
 * hexadecimal. A custom editor replaces both.
 *
 * <p>Anything else, and values beyond the type's range, is a type mismatch: {@code 400
 * MALFORMED_REQUEST}, the convention for unconvertible parameters ({@code docs/api.md}). Range rules
 * (e.g. {@code limit} 1–100) stay with Bean Validation ({@code 400 VALIDATION_FAILED}). An empty value
 * is "no value" for the wrapper types; a parameter's default applies before conversion.
 *
 * <p>A {@link ControllerAdvice} because binders are configured per request; it lives here (ArchUnit
 * R5) because its only effect is which requests are rejected as malformed.
 */
@ControllerAdvice
class DecimalIntegerParameters {

    private static final Pattern DECIMAL = Pattern.compile("^-?[0-9]+$");

    @InitBinder
    void acceptDecimalIntegersOnly(WebDataBinder binder) {
        binder.registerCustomEditor(int.class, new DecimalEditor(Integer::valueOf, false));
        binder.registerCustomEditor(Integer.class, new DecimalEditor(Integer::valueOf, true));
        binder.registerCustomEditor(long.class, new DecimalEditor(Long::valueOf, false));
        binder.registerCustomEditor(Long.class, new DecimalEditor(Long::valueOf, true));
    }

    private static final class DecimalEditor extends PropertyEditorSupport {

        private final Function<String, ? extends Number> parse;
        private final boolean allowEmpty;

        DecimalEditor(Function<String, ? extends Number> parse, boolean allowEmpty) {
            this.parse = parse;
            this.allowEmpty = allowEmpty;
        }

        @Override
        public void setAsText(String text) {
            if (text == null || text.isEmpty()) {
                if (!allowEmpty) {
                    throw new IllegalArgumentException("A value is required");
                }
                setValue(null);
                return;
            }
            if (!DECIMAL.matcher(text).matches()) {
                throw new IllegalArgumentException("Not a decimal integer");
            }
            // NumberFormatException (an IllegalArgumentException) beyond the type's range.
            setValue(parse.apply(text));
        }
    }
}
