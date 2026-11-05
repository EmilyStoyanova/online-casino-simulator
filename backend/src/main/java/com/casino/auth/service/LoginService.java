package com.casino.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.casino.auth.dto.LoginRequest;
import com.casino.auth.exception.InvalidCredentialsException;
import com.casino.auth.exception.TooManyAttemptsException;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.service.UserAccountService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Login (FR-2, FR-6): throttling, password verification with Spring Security's
 * {@code DaoAuthenticationProvider} and the record of the last login. Signing the user in to the
 * session is {@link SessionSignInService}'s job.
 *
 * <ol>
 *   <li>The account + IP and the IP counter are checked <em>before</em> the password (AC-6.5): a
 *       throttled attempt costs no BCrypt work and is answered the same way for every account.</li>
 *   <li>Every failure (unknown email, wrong password, disabled account) is counted for both keys and
 *       answered with the same {@code 401 INVALID_CREDENTIALS} (AC-2.2). For unknown emails the
 *       provider still hashes a dummy password, and a disabled account is only reported after the
 *       password check, so the response time does not reveal either.</li>
 *   <li>A success resets the account + IP counter (AC-6.6) and records {@code last_login_at}.</li>
 * </ol>
 */
@Service
public class LoginService {

    /** BCrypt's limit; registration rejects longer passwords, so such a login can never be right. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final AuthThrottleService throttle;
    private final ThrottleKeys throttleKeys;
    private final AuthenticationManager authenticationManager;
    private final UserAccountService accounts;

    public LoginService(AuthThrottleService throttle, ThrottleKeys throttleKeys, AuthenticationManager authenticationManager,
                        UserAccountService accounts) {
        this.throttle = throttle;
        this.throttleKeys = throttleKeys;
        this.authenticationManager = authenticationManager;
        this.accounts = accounts;
    }

    /**
     * @param clientIp {@code request.getRemoteAddr()}: the TCP peer, or the client named by a trusted
     *                 proxy (week-02.md §7.8); never a forwarded header read by the application
     * @return the profile of the signed-in user
     * @throws TooManyAttemptsException     if the account + IP or the IP is throttled
     * @throws InvalidCredentialsException  for every failed login
     */
    public UserProfileResponse login(LoginRequest request, String clientIp) {
        String email = UserAccountService.normalizeEmail(request.email());
        ThrottleKey accountIp = throttleKeys.loginAccountIp(email, clientIp);
        ThrottleKey ip = throttleKeys.loginIp(clientIp);
        throttle.checkAllowed(accountIp, ip);

        Authentication authentication;
        try {
            // BCrypt would truncate or reject longer input; it cannot be a stored password anyway.
            if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
                throw new BadCredentialsException("Password longer than BCrypt's limit");
            }
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
        } catch (InternalAuthenticationServiceException ex) {
            // E.g. the database is unreachable: a server error, not a failed login.
            throw new IllegalStateException("Login could not be completed", ex);
        } catch (AuthenticationException ex) {
            throttle.recordFailure(accountIp, ip);
            throw new InvalidCredentialsException();
        }
        throttle.reset(accountIp);
        return accounts.recordLogin(UUID.fromString(authentication.getName()));
    }
}
