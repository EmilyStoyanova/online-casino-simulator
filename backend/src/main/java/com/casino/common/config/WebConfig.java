package com.casino.common.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for {@code /api/**}, applied by Spring Security's CORS filter before authentication and CSRF,
 * so a preflight request needs neither a session nor a token.
 *
 * <p>Credentials (session cookie) are allowed, so origins are listed exactly. In development the Vite
 * dev server proxies {@code /api} with {@code changeOrigin}: the browser still sends
 * {@code Origin: http://localhost:5173} on unsafe requests, which is why that origin is allowed by default.
 *
 * <p>A request from an origin that is not allowed is rejected by Spring's CORS processor before any
 * application code: {@code 403} with the fixed plain-text body {@code Invalid CORS request} and no CORS
 * headers. This is the one error that is not Problem Details (documented in {@code docs/api.md}): the
 * browser never exposes it to the foreign page, and it contains nothing but that fixed text.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(corsProperties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Includes X-XSRF-TOKEN and Content-Type.
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
