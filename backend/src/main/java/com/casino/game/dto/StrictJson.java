package com.casino.game.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Strict JSON values for the fields of a spin request (Q10, {@code docs/plans/week-04.md} §4.5), applied per
 * field with {@code @JsonDeserialize}, so no other endpoint's parsing changes. A value of the wrong JSON
 * kind is an input mismatch, which Spring answers with {@code 400 MALFORMED_REQUEST}; nothing is rounded,
 * truncated or converted.
 *
 * <ul>
 *   <li>{@link OptionalInt}, {@link RequiredLong}: only a JSON integer token ({@code 10}); {@code 10.5},
 *       {@code 10.0}, {@code 1e1}, {@code "10"}, booleans, arrays and objects are rejected, and so is a
 *       token beyond the Java type (the contract's {@code int32}/{@code int64}). Integers within the type
 *       but outside the allowed range reach Bean Validation ({@code 400 VALIDATION_FAILED}).</li>
 *   <li>{@link EnumName}: only a JSON string with the exact name of a value. Jackson would otherwise read a
 *       number as the value's position ({@code "category": 0} as {@code STRAIGHT}).</li>
 * </ul>
 */
final class StrictJson {

    private StrictJson() {
    }

    private static void requireIntegerToken(JsonParser parser, DeserializationContext context, ValueDeserializer<?> source) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            context.reportInputMismatch(source, "Expected a JSON integer without fraction or exponent");
        }
    }

    /** An {@code int32} field; JSON {@code null} counts as omitted (an option property of another category). */
    static final class OptionalInt extends ValueDeserializer<Integer> {

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            requireIntegerToken(parser, context, this);
            return parser.getIntValue();
        }
    }

    /** A required {@code int64} field: an explicit JSON {@code null} is not an integer either. */
    static final class RequiredLong extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            requireIntegerToken(parser, context, this);
            return parser.getLongValue();
        }

        /** An explicit JSON {@code null}: not an integer token, so the request is malformed. */
        @Override
        public Long getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(this, "Expected a JSON integer, not null");
        }

        /**
         * The property is missing: {@code null}, so that Bean Validation reports it with its field name
         * ({@code 400 VALIDATION_FAILED}, contract). Jackson would otherwise use {@link #getNullValue}.
         */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /** An enum field: the exact name of one of its values, as a JSON string; JSON {@code null} counts as omitted. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class EnumName extends ValueDeserializer<Enum<?>> {

        private final Class<? extends Enum> type;

        EnumName() {
            this(null);
        }

        private EnumName(Class<? extends Enum> type) {
            this.type = type;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            return new EnumName((Class<? extends Enum>) property.getType().getRawClass());
        }

        @Override
        public Enum<?> deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(this, "Expected the name of a value as a JSON string");
            }
            String name = parser.getValueAsString();
            for (Enum<?> value : type.getEnumConstants()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return context.reportInputMismatch(this, "Not one of the allowed values");
        }
    }
}
