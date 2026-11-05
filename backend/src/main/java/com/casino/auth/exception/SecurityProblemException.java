package com.casino.auth.exception;

import java.io.IOException;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Carries the code of a Spring Security {@code 401}/{@code 403} to the {@code GlobalExceptionHandler}.
 *
 * <p>The entry point and the access-denied handler run in the filter chain, outside Spring MVC.
 * Resolving this exception with Spring MVC's {@link HandlerExceptionResolver} produces exactly the
 * same Problem Details as every other error (content negotiation, {@code no-store}), without a second
 * way of writing error bodies.
 */
final class SecurityProblemException extends ApiException {

    private SecurityProblemException(ProblemCode code) {
        super(code);
    }

    static void respond(HandlerExceptionResolver resolver, HttpServletRequest request, HttpServletResponse response,
                        ProblemCode code) throws IOException {
        if (resolver.resolveException(request, response, null, new SecurityProblemException(code)) == null) {
            // Not expected; the /error fallback then still answers with the status and its generic code.
            response.sendError(code.status().value());
        }
    }
}
