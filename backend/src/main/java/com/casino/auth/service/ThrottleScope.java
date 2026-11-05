package com.casino.auth.service;

/** What a throttling counter limits (week-02.md §7.7); the limits are in {@code app.security.throttle.*}. */
public enum ThrottleScope {

    /** Failed logins for one account from one client IP; only that source is blocked (no account lockout). */
    LOGIN_ACCOUNT_IP,

    /** Failed logins from one client IP for any accounts. */
    LOGIN_IP,

    /** Registration attempts from one client IP, including those that end in {@code 409}. */
    REGISTER_IP
}
