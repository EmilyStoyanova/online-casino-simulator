package com.casino.auth.controller;

import com.casino.auth.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Counts every registration request as an attempt (AC-1.8) before Spring MVC reads and validates its
 * body, so requests that end in {@code 400} count too and a throttled client gets {@code 429} first.
 *
 * <p>Runs after the security filter chain: a request without a valid CSRF token is rejected with
 * {@code 403} before it is counted, so a foreign page cannot use up a visitor's limit. A
 * {@code TooManyAttemptsException} thrown here is rendered by {@code GlobalExceptionHandler}.
 */
public class RegistrationAttemptInterceptor implements HandlerInterceptor {

    private final ObjectProvider<RegistrationService> registrationService;

    /** Resolved per request, so Spring MVC test slices without the service still start. */
    public RegistrationAttemptInterceptor(ObjectProvider<RegistrationService> registrationService) {
        this.registrationService = registrationService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Only the registration itself (not e.g. an OPTIONS request answered by Spring MVC).
        if (HttpMethod.POST.matches(request.getMethod())) {
            registrationService.getObject().countAttempt(request.getRemoteAddr());
        }
        return true;
    }
}
