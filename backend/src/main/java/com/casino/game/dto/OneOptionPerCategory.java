package com.casino.game.dto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A bet carries exactly the option property of its category (contract {@code RouletteBet}, Q1a): the
 * option of the category must be present, the other four must be omitted ({@code null} counts as omitted).
 * Each problem is reported under the property's own name, e.g. {@code bets[1].color} "must not be null" or
 * {@code bets[0].dozen} "must be omitted for this category". A missing category is reported by
 * {@code @NotNull} alone.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OneOptionPerCategory.Validator.class)
public @interface OneOptionPerCategory {

    String message() default "must have exactly the option of its category";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Checks one {@link RouletteBetRequest}. */
    class Validator implements ConstraintValidator<OneOptionPerCategory, RouletteBetRequest> {

        @Override
        public boolean isValid(RouletteBetRequest bet, ConstraintValidatorContext context) {
            if (bet == null || bet.category() == null) {
                return true;
            }
            String required = switch (bet.category()) {
                case STRAIGHT -> "number";
                case RED_BLACK -> "color";
                case EVEN_ODD -> "parity";
                case LOW_HIGH -> "range";
                case DOZEN -> "dozen";
            };
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("number", bet.number());
            options.put("color", bet.color());
            options.put("parity", bet.parity());
            options.put("range", bet.range());
            options.put("dozen", bet.dozen());

            boolean valid = true;
            context.disableDefaultConstraintViolation();
            for (Map.Entry<String, Object> option : options.entrySet()) {
                boolean expected = option.getKey().equals(required);
                if (expected && option.getValue() == null) {
                    violation(context, option.getKey(), "must not be null");
                    valid = false;
                } else if (!expected && option.getValue() != null) {
                    violation(context, option.getKey(), "must be omitted for this category");
                    valid = false;
                }
            }
            return valid;
        }

        private static void violation(ConstraintValidatorContext context, String property, String message) {
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(property).addConstraintViolation();
        }
    }
}
