package com.casino.common.exception;

/**
 * One invalid request property: an entry of {@code errors} in a {@code VALIDATION_FAILED} problem
 * ({@code FieldError} in {@code openapi.yaml}). The rejected value is never included.
 *
 * @param field   name of the request property, e.g. {@code password}
 * @param message what is wrong with the value
 */
public record FieldViolation(String field, String message) {
}
