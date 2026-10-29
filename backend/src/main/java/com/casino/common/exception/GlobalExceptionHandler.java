package com.casino.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralised error handling for all REST controllers.
 *
 * <p>Every error is returned as RFC 9457 Problem Details ({@code application/problem+json}),
 * matching the {@code Problem} schema in {@code openapi.yaml}. Standard Spring MVC exceptions
 * (unknown path, unsupported method, unreadable body, …) are mapped by
 * {@link ResponseEntityExceptionHandler}; anything unexpected becomes a generic 500 without
 * exposing internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later.");
        problem.setTitle("Internal Server Error");
        return problem;
    }
}
