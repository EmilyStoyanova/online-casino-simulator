package com.casino.auth.config;

import java.util.Map;

import com.casino.auth.exception.ProblemAccessDeniedHandler;
import com.casino.auth.exception.ProblemAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security configuration (ADR-0006, {@code docs/plans/week-02.md} §7.1–7.2).
 *
 * <p>Filter chain: CORS → Spring Session (servlet filter before this chain) → security context →
 * CSRF → authorization (deny by default) → controllers. Login, logout and registration are controller
 * endpoints (contract-testable), so form login, HTTP Basic and the logout filter are disabled.
 * Sessions live in PostgreSQL (Spring Session JDBC); cookie and timeout settings come from
 * {@code application.yml}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final String BCRYPT = "bcrypt";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        // Public paths are permitted for every method: the rules decide who may call a path, Spring MVC
        // which methods it supports. So HEAD works wherever GET does, and an unsupported method answers
        // 405 instead of 401. Unsafe methods still need a CSRF token first.
        http.authorizeHttpRequests(requests -> requests
                        // Public operations of openapi.yaml (`security: []`).
                        .requestMatchers("/api/v1/system/status", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // API documentation (development; disabled in production in Week 12).
                        .requestMatchers("/openapi.yaml", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        // Errors forwarded by the container must never turn into 401.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // XSRF-TOKEN cookie (readable by JavaScript) + X-XSRF-TOKEN header on every unsafe method.
                .csrf(csrf -> csrf.spa())
                // Uses the CorsConfigurationSource bean (common.config.WebConfig).
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // No saved request (and therefore no session) for anonymous 401s; the SPA handles navigation.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint(exceptionResolver))
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(exceptionResolver)))
                // Spring Security's default headers (nosniff, DENY framing, no-store, HSTS on HTTPS) + no referrer.
                .headers(headers -> headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    /** BCrypt with the configured cost; hashes are stored as {@code {bcrypt}$2a$12$…}. */
    @Bean
    PasswordEncoder passwordEncoder(SecurityProperties properties) {
        return new DelegatingPasswordEncoder(BCRYPT, Map.of(BCRYPT, new BCryptPasswordEncoder(properties.bcryptStrength())));
    }
}
