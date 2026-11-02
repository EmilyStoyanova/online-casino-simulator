package com.casino.common.exception.controller;

import com.casino.common.exception.ProblemDetailFactory;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's {@code BasicErrorController} (which backs off when an {@link ErrorController}
 * bean exists) for errors that never reach a controller, e.g. an exception thrown by a servlet filter
 * or a {@code sendError} call. The servlet container forwards them to {@code server.error.path}
 * ({@code /error}); this controller answers with the same Problem Details as the
 * {@code GlobalExceptionHandler}: the forwarded status is kept, with its generic code, a generic
 * detail and {@code no-store}. The exception and the {@code sendError} message are never exposed; the
 * container already logs the exception.
 *
 * <ul>
 *   <li>A direct request to {@code /error} (no forwarded status) is answered with {@code 404 NOT_FOUND}.</li>
 *   <li>A forwarded status that is not an error status becomes {@code 500 INTERNAL_ERROR}.</li>
 *   <li>Spring Security (W2-04): {@code /error} must be permitted for all dispatcher types, so that an
 *       error never turns into a {@code 401}. A {@code sendError(401/403)} from a security filter is
 *       answered here with {@code AUTHENTICATION_REQUIRED}/{@code ACCESS_DENIED}; the specific codes
 *       ({@code SESSION_EXPIRED}, {@code CSRF_TOKEN_INVALID}, …) are written by the entry point and
 *       access-denied handler themselves.</li>
 * </ul>
 *
 * <p>Hidden from the generated specification, because it is infrastructure, not an API operation.
 */
@Hidden
@RestController
@RequestMapping("${server.error.path:/error}")
class ErrorFallbackController implements ErrorController {

    @RequestMapping
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        String path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri
                ? uri : request.getRequestURI();
        ProblemDetail problem = ProblemDetailFactory.forStatus(status(request), path);
        return ResponseEntity.status(problem.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    private static int status(HttpServletRequest request) {
        if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status)) {
            return HttpStatus.NOT_FOUND.value();
        }
        return status >= 400 && status < 600 ? status : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
