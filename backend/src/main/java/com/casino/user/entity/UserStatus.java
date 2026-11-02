package com.casino.user.entity;

/** Status of an account ({@code users.status}). */
public enum UserStatus {
    /** Can sign in. */
    ACTIVE,
    /** Cannot sign in (set by administrators, Week 10). */
    DISABLED
}
