package com.casino.user.entity;

/** Role of an account ({@code users.role}); exactly one per account. */
public enum Role {
    /** Every registered user. */
    PLAYER,
    /** Administrator; never assigned through the API (created from environment configuration). */
    ADMIN
}
