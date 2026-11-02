package com.casino.auth.config;

import java.util.Locale;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to start with a forwarded-header setup that lets clients choose their own IP address
 * ({@code request.getRemoteAddr()}), which throttling relies on (week-02.md §7.8):
 * <ul>
 *   <li>{@code none} (default): {@code X-Forwarded-*} headers are ignored; the address is the TCP peer.</li>
 *   <li>{@code native}: Tomcat's {@code RemoteIpValve} takes the right-most untrusted
 *       {@code X-Forwarded-For} entry, but only from the proxies in
 *       {@code server.tomcat.remoteip.internal-proxies}. That list must be set explicitly: Spring Boot's
 *       default trusts every private address range.</li>
 *   <li>{@code framework} is rejected: Spring's {@code ForwardedHeaderFilter} trusts the headers of every
 *       client.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ForwardedHeadersCheck {

    static final String STRATEGY = "server.forward-headers-strategy";
    static final String INTERNAL_PROXIES = "server.tomcat.remoteip.internal-proxies";

    public ForwardedHeadersCheck(Environment environment) {
        Binder binder = Binder.get(environment);
        String strategy = binder.bind(STRATEGY, String.class).orElse("none").strip().toLowerCase(Locale.ROOT);
        switch (strategy) {
            case "none" -> {
            }
            case "native" -> {
                if (!binder.bind(INTERNAL_PROXIES, String.class).isBound()) {
                    throw new IllegalStateException(STRATEGY + "=native requires an explicit " + INTERNAL_PROXIES
                            + " (SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES) listing only the hosting platform's proxies");
                }
            }
            default -> throw new IllegalStateException(STRATEGY + "=" + strategy
                    + " is not allowed: use none, or native with an explicit " + INTERNAL_PROXIES);
        }
    }
}
