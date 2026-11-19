package com.casino.game.dto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * No selection (category and option) twice in one spin, whatever the amounts (contract
 * {@code RouletteSpinRequest}): reported as {@code bets} "must not contain the same selection twice".
 * Bets without a category are left to their own validation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DistinctSelections.Validator.class)
public @interface DistinctSelections {

    String message() default "must not contain the same selection twice";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Checks one {@link RouletteSpinRequest}. */
    class Validator implements ConstraintValidator<DistinctSelections, RouletteSpinRequest> {

        @Override
        public boolean isValid(RouletteSpinRequest request, ConstraintValidatorContext context) {
            if (request == null || request.bets() == null) {
                return true;
            }
            Set<List<Object>> selections = new HashSet<>();
            for (RouletteBetRequest bet : request.bets()) {
                if (bet == null || bet.category() == null) {
                    continue;
                }
                List<Object> selection = Arrays.asList(bet.category(), bet.number(), bet.color(), bet.parity(), bet.range(), bet.dozen());
                if (!selections.add(selection)) {
                    context.disableDefaultConstraintViolation();
                    context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                            .addPropertyNode("bets").addConstraintViolation();
                    return false;
                }
            }
            return true;
        }
    }
}
