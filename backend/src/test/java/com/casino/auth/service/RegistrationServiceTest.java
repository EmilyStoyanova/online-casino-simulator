package com.casino.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.casino.auth.config.RegistrationProperties;
import com.casino.auth.config.ThrottleProperties;
import com.casino.auth.config.ThrottleProperties.Limit;
import com.casino.auth.dto.RegisterRequest;
import com.casino.auth.exception.RegistrationDisabledException;
import com.casino.auth.exception.TooManyAttemptsException;
import com.casino.common.exception.ProblemCode;
import com.casino.user.dto.NewAccount;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRole;
import com.casino.user.exception.EmailAlreadyRegisteredException;
import com.casino.user.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

class RegistrationServiceTest {

    private static final String IP = "203.0.113.7";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String HASH = "{bcrypt}$2a$12$hash";
    private static final RegisterRequest REQUEST = new RegisterRequest("alice@example.com", PASSWORD, "Alice", true);

    private final AuthThrottleService throttle = mock(AuthThrottleService.class);
    private final ThrottleKeys keys = new ThrottleKeys(new ThrottleProperties(new Limit(5, Duration.ofMinutes(15)),
            new Limit(50, Duration.ofMinutes(15)), new Limit(10, Duration.ofHours(1)), null, true));
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final RegistrationService service = new RegistrationService(throttle, keys, passwordEncoder, accounts,
            new RegistrationProperties(true));

    @Test
    void anAttemptIsCountedForTheRegisterScopeOfTheClientIp() {
        service.countAttempt(IP);

        verify(throttle).acquire(keys.registerIp(IP));
        verifyNoInteractions(passwordEncoder, accounts);
    }

    @Test
    void aThrottledAttemptIsRejected() {
        doThrow(new TooManyAttemptsException(Duration.ofMinutes(5))).when(throttle).acquire(keys.registerIp(IP));

        assertThatThrownBy(() -> service.countAttempt(IP)).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void registrationStoresOnlyTheHash() {
        UserProfileResponse profile = new UserProfileResponse(UUID.randomUUID(), "alice@example.com", "Alice", UserRole.PLAYER,
                List.of(), Instant.now(), Instant.now());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(accounts.createAccount(any())).thenReturn(profile);

        assertThat(service.register(REQUEST)).isSameAs(profile);

        InOrder order = inOrder(passwordEncoder, accounts);
        order.verify(passwordEncoder).encode(PASSWORD);
        ArgumentCaptor<NewAccount> account = ArgumentCaptor.forClass(NewAccount.class);
        order.verify(accounts).createAccount(account.capture());
        assertThat(account.getValue()).isEqualTo(new NewAccount("alice@example.com", HASH, "Alice", true));
        // Counting is countAttempt's job, before the body is validated.
        verifyNoInteractions(throttle);
    }

    @Test
    void aDuplicateEmailIsReported() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(accounts.createAccount(any())).thenThrow(new EmailAlreadyRegisteredException());

        assertThatThrownBy(() -> service.register(REQUEST)).isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void closedRegistrationRejectsBeforeCountingOrCreatingAnything() {
        RegistrationService closed = new RegistrationService(throttle, keys, passwordEncoder, accounts,
                new RegistrationProperties(false));

        assertThatThrownBy(() -> closed.countAttempt(IP)).isInstanceOf(RegistrationDisabledException.class)
                .extracting(error -> ((RegistrationDisabledException) error).code())
                .isEqualTo(ProblemCode.REGISTRATION_DISABLED);
        assertThatThrownBy(() -> closed.register(REQUEST)).isInstanceOf(RegistrationDisabledException.class);
        verifyNoInteractions(throttle, passwordEncoder, accounts);
    }
}
