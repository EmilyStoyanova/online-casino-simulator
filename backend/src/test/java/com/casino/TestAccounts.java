package com.casino;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.casino.wallet.service.WalletService;
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

    /**
     * An active player with a wallet holding the starting balance, as after a registration. Users
     * inserted by {@link #active} have no wallet (they bypass registration), and {@code GET /api/v1/wallet}
     * answers {@code 500} for them (AC-2.6).
     */
    public UUID activePlayerWithWallet(String email, String password, WalletService walletService) {
        UUID user = active(email, password);
        walletService.openWallet(user);
        return user;
    }

    /** An active administrator (as the bootstrap creates one): no wallet. */
    public UUID activeAdministrator(String email, String password) {
        UUID user = active(email, password);
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user);
        return user;
    }

    /**
     * Deletes the rows that login tests create, for tests with the {@code render} profile only: there the
     * guarded {@link TestDatabase#reset()} refuses (G4). Fails on users with a wallet
     * ({@code fk_wallets_user}); every other test cleans up with {@link TestDatabase#reset()}.
     */
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
