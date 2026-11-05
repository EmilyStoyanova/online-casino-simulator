package com.casino.auth.service;

import com.casino.auth.dto.RegisterRequest;
import com.casino.auth.exception.TooManyAttemptsException;
import com.casino.user.dto.NewAccount;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.exception.EmailAlreadyRegisteredException;
import com.casino.user.service.UserAccountService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registration of a player account (FR-1): throttling per client IP, password hashing and account
 * creation. Signing the new user in is {@link SessionSignInService}'s job.
 *
 * <p>Every registration request is first counted with {@link #countAttempt}, before its body is read
 * and validated (by {@code auth.controller.RegistrationAttemptInterceptor}), then handled by
 * {@link #register}.
 */
@Service
public class RegistrationService {

    private final AuthThrottleService throttle;
    private final ThrottleKeys throttleKeys;
    private final PasswordEncoder passwordEncoder;
    private final UserAccountService accounts;

    public RegistrationService(AuthThrottleService throttle, ThrottleKeys throttleKeys, PasswordEncoder passwordEncoder,
                               UserAccountService accounts) {
        this.throttle = throttle;
        this.throttleKeys = throttleKeys;
        this.passwordEncoder = passwordEncoder;
        this.accounts = accounts;
    }

    /**
     * Counts one registration attempt of {@code clientIp} and rejects it above the limit (AC-1.8).
     * Atomic, so the limit is exact for concurrent requests. Every attempt counts, whether it ends in
     * {@code 201}, {@code 400} or {@code 409}.
     *
     * @param clientIp {@code request.getRemoteAddr()}: the TCP peer, or the client named by a trusted
     *                 proxy (week-02.md §7.8); never a forwarded header read by the application
     * @throws TooManyAttemptsException if the client exceeded the registration limit
     */
    public void countAttempt(String clientIp) {
        throttle.acquire(throttleKeys.registerIp(clientIp));
    }

    /**
     * Creates the account of a valid request (Bean Validation, including {@code PasswordPolicy}, has
     * already passed, and the attempt was counted).
     *
     * @throws EmailAlreadyRegisteredException if the email already has an account
     */
    public UserProfileResponse register(RegisterRequest request) {
        String passwordHash = passwordEncoder.encode(request.password());
        return accounts.createAccount(new NewAccount(request.email(), passwordHash, request.displayName(),
                Boolean.TRUE.equals(request.adultConfirmed())));
    }
}
