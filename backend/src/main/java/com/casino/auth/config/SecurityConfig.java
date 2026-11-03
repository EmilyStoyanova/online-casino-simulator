package com.casino.auth.config;

import java.time.Clock;
import java.util.Map;

import com.casino.auth.exception.ProblemAccessDeniedHandler;
import com.casino.auth.exception.ProblemAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security configuration (ADR-0006, {@code docs/plans/week-02.md} §7.1–7.2).
 *
 * <p>Filter chain: Spring Session (servlet filter before this chain) → absolute session lifetime →
 * security context → CORS → CSRF → authorization (deny by default) → controllers. Login, logout and registration are controller
 * endpoints (contract-testable), so form login, HTTP Basic and the logout filter are disabled.
 * Sessions live in PostgreSQL (Spring Session JDBC); cookie and timeout settings come from
 * {@code application.yml}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecurityProperties.class, ThrottleProperties.class})
public class SecurityConfig {

    private static final String BCRYPT = "bcrypt";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
                                            CsrfTokenRepository csrfTokenRepository,
                                            SecurityContextRepository securityContextRepository,
                                            SecurityProperties properties,
                                            ObjectProvider<Clock> clock)
            throws Exception {
        // The application's Clock; web test slices without it use the system clock.
        AbsoluteSessionTimeoutFilter absoluteTimeout = new AbsoluteSessionTimeoutFilter(
                properties.session().absoluteTimeout(), () -> clock.getIfAvailable(Clock::systemUTC));
        // Public paths are permitted for every method: the rules decide who may call a path, Spring MVC
        // which methods it supports. So HEAD works wherever GET does, and an unsupported method answers
        // 405 instead of 401. Unsafe methods still need a CSRF token first.
        http.authorizeHttpRequests(requests -> requests
                        // Public operations of openapi.yaml (`security: []`).
                        .requestMatchers("/api/v1/system/status", "/api/v1/auth/csrf", "/api/v1/auth/register",
                                "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // API documentation (development; disabled in production in Week 12).
                        .requestMatchers("/openapi.yaml", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        // Errors forwarded by the container must never turn into 401.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // XSRF-TOKEN cookie (readable by JavaScript) + X-XSRF-TOKEN header on every unsafe method.
                // The repository is the same as spa()'s, shared with SessionSignInService (token rotation).
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                // Shared with SessionSignInService, which stores the context of a newly signed-in user.
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // 12 h absolute lifetime: ends an old session before its security context is loaded.
                .addFilterBefore(absoluteTimeout, SecurityContextHolderFilter.class)
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

    /** The {@code XSRF-TOKEN} cookie, readable by JavaScript ({@code csrf.spa()}'s repository). */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /** Spring Security's default: the request (for this request) and the HTTP session (Spring Session). */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    /** BCrypt with the configured cost; hashes are stored as {@code {bcrypt}$2a$12$…}. */
    @Bean
    PasswordEncoder passwordEncoder(SecurityProperties properties) {
        return new DelegatingPasswordEncoder(BCRYPT, Map.of(BCRYPT, new BCryptPasswordEncoder(properties.bcryptStrength())));
    }
}
