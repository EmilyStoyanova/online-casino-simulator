package com.casino.common.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser origins allowed to call the API cross-origin.
 *
 * <p>In local development the Vite dev server proxies {@code /api} requests, so CORS is
 * only needed when the frontend is served from a different origin (e.g. production).
 *
 * @param allowedOrigins exact origins such as {@code https://casino.example.org}; wildcards are not allowed
 *                       because the API will use credentialed (cookie) requests
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
