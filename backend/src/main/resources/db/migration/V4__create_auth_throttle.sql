-- Fixed-window counters for login and registration throttling (docs/plans/week-02.md §6, §7.7).
-- One row per key; AuthThrottleRepository starts or increments a window in one atomic upsert.
-- Keys are '<scope>:<sha-256 hex>' of the normalised email and/or client IP: no raw personal data.
CREATE TABLE auth_throttle (
    throttle_key      VARCHAR(90) PRIMARY KEY,
    attempt_count     INTEGER     NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    -- Only hashed keys can be stored, never an email or IP address by mistake.
    CONSTRAINT ck_auth_throttle_key_hashed    CHECK (throttle_key ~ '^[A-Z][A-Z_]*:[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_throttle_attempt_count CHECK (attempt_count > 0)
);

-- Cleanup of ended windows (hourly job).
CREATE INDEX ix_auth_throttle_window_started_at ON auth_throttle (window_started_at);
