package com.casino.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.casino.auth.config.ThrottleProperties;
import com.casino.auth.config.ThrottleProperties.Limit;
import com.casino.auth.dto.LoginRequest;
import com.casino.auth.exception.InvalidCredentialsException;
import com.casino.auth.exception.TooManyAttemptsException;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRole;
import com.casino.user.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class LoginServiceTest {

    private static final String IP = "203.0.113.7";
    private static final String PASSWORD = "correct-horse-battery";
    private static final UUID ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");

    private final AuthThrottleService throttle = mock(AuthThrottleService.class);
    private final ThrottleKeys keys = new ThrottleKeys(new ThrottleProperties(new Limit(5, Duration.ofMinutes(15)),
            new Limit(50, Duration.ofMinutes(15)), new Limit(10, Duration.ofHours(1)), null, true));
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final LoginService service = new LoginService(throttle, keys, authenticationManager, accounts);

    private final ThrottleKey accountIp = keys.loginAccountIp("alice@example.com", IP);
    private final ThrottleKey ip = keys.loginIp(IP);

    @Test
    void checksTheLimitsFirstThenAuthenticatesTheNormalisedEmailAndResetsTheAccountCounter() {
        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(ID.toString(), null, List.of());
        when(authenticationManager.authenticate(any())).thenReturn(authenticated);
        UserProfileResponse profile = new UserProfileResponse(ID, "alice@example.com", "Alice", UserRole.PLAYER, List.of(),
                Instant.now(), Instant.now());
        when(accounts.recordLogin(ID)).thenReturn(profile);

        assertThat(service.login(new LoginRequest("  Alice@Example.COM ", PASSWORD), IP)).isSameAs(profile);

        InOrder order = inOrder(throttle, authenticationManager, accounts);
        // AC-6.5: before the password is verified.
        order.verify(throttle).checkAllowed(accountIp, ip);
        ArgumentCaptor<Authentication> attempt = ArgumentCaptor.forClass(Authentication.class);
        order.verify(authenticationManager).authenticate(attempt.capture());
        assertThat(attempt.getValue().getName()).isEqualTo("alice@example.com");
        assertThat(attempt.getValue().getCredentials()).isEqualTo(PASSWORD);
        // AC-6.6: only the account + IP counter is reset.
        order.verify(throttle).reset(accountIp);
        order.verify(accounts).recordLogin(ID);
        verify(throttle, never()).recordFailure(any(ThrottleKey[].class));
        verify(throttle, never()).reset(ip);
    }

    @Test
    void everyFailureIsCountedForBothKeysAndReportedTheSameWay() {
        for (RuntimeException failure : List.of(new BadCredentialsException("wrong password or unknown email"),
                new DisabledException("disabled"))) {
            doThrow(failure).when(authenticationManager).authenticate(any());

            assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", PASSWORD), IP))
                    .isExactlyInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("The email or password is incorrect.");
        }
        verify(throttle, times(2)).recordFailure(accountIp, ip);
        verify(throttle, never()).reset(any());
        verifyNoInteractions(accounts);
    }

    @Test
    void aThrottledAttemptVerifiesNoPassword() {
        doThrow(new TooManyAttemptsException(Duration.ofMinutes(3))).when(throttle).checkAllowed(accountIp, ip);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", PASSWORD), IP))
                .isInstanceOf(TooManyAttemptsException.class);
        verifyNoInteractions(authenticationManager, accounts);
        verify(throttle, never()).recordFailure(any(ThrottleKey[].class));
    }

    @Test
    void aPasswordLongerThanBcryptsLimitIsAFailedLoginWithoutHashing() {
        assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", "a".repeat(73)), IP))
                .isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(authenticationManager);
        verify(throttle).recordFailure(accountIp, ip);
    }

    @Test
    void anInternalErrorIsNotAFailedLogin() {
        when(authenticationManager.authenticate(any())).thenThrow(new InternalAuthenticationServiceException("database down"));

        assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", PASSWORD), IP))
                .isInstanceOf(IllegalStateException.class);
        verify(throttle, never()).recordFailure(any(ThrottleKey[].class));
    }

    @Test
    void loginRequestNeverShowsThePassword() {
        assertThat(new LoginRequest("alice@example.com", PASSWORD).toString()).doesNotContain(PASSWORD);
    }
}
