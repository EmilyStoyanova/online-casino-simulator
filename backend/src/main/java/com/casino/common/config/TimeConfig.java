package com.casino.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of the current time. Components inject {@link Clock} instead of calling
 * {@code Instant.now()} directly, so time-dependent rules (session limits, daily reward
 * limits) can be tested deterministically.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
