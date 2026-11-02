package com.casino.auth.service;

/**
 * The counter a throttled attempt belongs to: {@code <scope>:<hmac-sha-256 hex>}, created by
 * {@link ThrottleKeys} only. {@link #toString()} shows only the scope.
 */
public final class ThrottleKey {

    private final ThrottleScope scope;
    private final String value;

    ThrottleKey(ThrottleScope scope, String hmacHex) {
        this.scope = scope;
        this.value = scope.name() + ':' + hmacHex;
    }

    public ThrottleScope scope() {
        return scope;
    }

    /** The stored key ({@code auth_throttle.throttle_key}). */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ThrottleKey key && value.equals(key.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "ThrottleKey[" + scope + "]";
    }
}
