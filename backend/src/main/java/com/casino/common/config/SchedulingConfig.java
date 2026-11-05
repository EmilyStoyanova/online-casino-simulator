package com.casino.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs the {@code @Scheduled} maintenance jobs of the modules (e.g. the cleanup of ended throttling
 * windows) on Spring Boot's auto-configured task scheduler.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
