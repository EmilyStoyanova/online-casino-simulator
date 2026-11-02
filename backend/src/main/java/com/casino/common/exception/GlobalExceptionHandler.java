package com.casino.common.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralised error handling for all REST controllers.
 *
 * <p>Every error is returned as RFC 9457 Problem Details ({@code application/problem+json}) with a
 * stable {@link ProblemCode}, built by {@link ProblemDetailFactory} and sent with
 * {@code Cache-Control: no-store}:
 * <ul>
 *   <li>{@link ApiException}s of the modules keep their code and detail;</li>
 *   <li>validation failures become {@code VALIDATION_FAILED} with one {@link FieldViolation} per
 *       invalid property;</li>
 *   <li>the other Spring MVC exceptions (unknown path, unsupported method or media type, unreadable
 *       body, {@code ResponseStatusException}, …), handled by {@link ResponseEntityExceptionHandler},
 *       keep their status and get its generic code ({@link ProblemCode#forStatus}). Spring's own
 *       detail is replaced, because it may contain request values or class names;</li>
 *   <li>anything unexpected becomes a generic {@code 500 INTERNAL_ERROR}; the exception is only
 *       logged.</li>
 * </ul>
 *
 * <p>Spring Security's {@link AuthenticationException} and {@link AccessDeniedException} thrown by a
 * controller or service are rethrown instead of becoming a {@code 500}, so that Spring Security's
 * entry point and access-denied handler answer them with {@code 401}/{@code 403}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String INVALID_VALUE = "has an invalid value";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return respond(ex, ProblemDetailFactory.create(ex.code(), ex.getMessage(), path(request)), new HttpHeaders(), request);
    }

    /** Lets Spring Security's {@code ExceptionTranslationFilter} handle them (see the class documentation). */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void rethrowSecurityException(Exception ex) throws Exception {
        throw ex;
    }

    /** Returns {@link ProblemDetail} directly, so springdoc documents the 500 as {@code application/problem+json}. */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ProblemDetail handleUnexpected(Exception ex, WebRequest request, HttpServletResponse response) {
        log.error("Unhandled exception", ex);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return ProblemDetailFactory.create(ProblemCode.INTERNAL_ERROR, path(request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> violation(error, error.getObjectName()))
                .toList();
        return respond(ex, ProblemDetailFactory.validationFailed(violations, path(request)), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        if (ex.isForReturnValue()) {
            // An invalid return value is a server bug, not a client error.
            return handleExceptionInternal(ex, null, headers, status, request);
        }
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String parameter = Objects.requireNonNullElse(result.getMethodParameter().getParameterName(),
                    "parameter" + result.getMethodParameter().getParameterIndex());
            result.getResolvableErrors().forEach(error -> violations.add(violation(error, parameter)));
        }
        return respond(ex, ProblemDetailFactory.validationFailed(violations, path(request)), headers, request);
    }

    /**
     * Every other Spring MVC exception, including {@code ResponseStatusException}: its status is kept,
     * with the generic code of that status and without Spring's detail.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.isError() ? statusCode.value() : HttpStatus.INTERNAL_SERVER_ERROR.value();
        return respond(ex, ProblemDetailFactory.forStatus(status, path(request)), headers, request);
    }

    private ResponseEntity<Object> respond(Exception ex, ProblemDetail problem, HttpHeaders headers, WebRequest request) {
        HttpStatusCode status = HttpStatusCode.valueOf(problem.getStatus());
        if (status.is5xxServerError()) {
            log.error("Request failed with status {}", status.value(), ex);
        }
        HttpHeaders responseHeaders = HttpHeaders.copyOf(headers);
        responseHeaders.setCacheControl(CacheControl.noStore());
        return super.handleExceptionInternal(ex, problem, responseHeaders, status, request);
    }

    /** Field name and message of a validation error; never the rejected value. */
    private static FieldViolation violation(MessageSourceResolvable error, String field) {
        if (error instanceof FieldError fieldError) {
            // The message of a conversion failure contains the rejected value and the exception text.
            return new FieldViolation(fieldError.getField(), fieldError.isBindingFailure() ? INVALID_VALUE : message(error));
        }
        return new FieldViolation(field, message(error));
    }

    private static String message(MessageSourceResolvable error) {
        return Objects.requireNonNullElse(error.getDefaultMessage(), INVALID_VALUE);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI() : null;
    }
}
