package com.casino;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates accounts directly in {@code users} for login tests, including disabled ones, which the API
 * cannot create. The BCrypt hash of each password is computed once per test run.
 */
public final class TestAccounts {

    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public TestAccounts(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public UUID active(String email, String password) {
        return create(email, password, "ACTIVE");
    }

    public UUID disabled(String email, String password) {
        return create(email, password, "DISABLED");
    }

    /** Deletes the rows that login tests create. */
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM auth_throttle");
    }

    private UUID create(String email, String password, String status) {
        String hash = HASHES.computeIfAbsent(password, passwordEncoder::encode);
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at, status)
                VALUES (?, ?, ?, now(), ?) RETURNING id""", UUID.class, email, hash, email.substring(0, email.indexOf('@')), status);
    }
}
