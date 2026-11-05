package com.casino.auth.repository;

import java.time.Instant;

/**
 * State of one throttling counter ({@code auth_throttle} row).
 *
 * @param attemptCount    attempts counted in the window
 * @param windowStartedAt when the window began (with its first attempt)
 */
public record ThrottleWindow(int attemptCount, Instant windowStartedAt) {
}
