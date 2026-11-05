package com.casino.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.casino.user.dto.NewAccount;
import com.casino.user.dto.UserCredentials;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRole;
import com.casino.user.exception.EmailAlreadyRegisteredException;
import com.casino.user.service.UserAccountService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** {@link AdminBootstrapRunner} (AC-9.4, S16). */
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapRunnerTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String EMAIL = "admin@example.com";
    private static final String PASSWORD = "bootstrap-admin-pass-7f3e";
    private static final String HASH = "{bcrypt}$2a$12$abcdefghijklmnopqrstuuJ0b0t3Zl0Q1zU0z9eXv7m8e3a1b2c3d4";
    private static final UUID ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");

    private final UserAccountService accounts = mock(UserAccountService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @Test
    void isDisabledWithoutConfiguration() {
        runner(null, null).run(null);
        runner("  ", "").run(null);

        verifyNoInteractions(accounts, passwordEncoder);
    }

    @Test
    void refusesToStartWithOnlyOneValue() {
        for (AdminBootstrapRunner runner : List.of(runner(EMAIL, null), runner(null, PASSWORD), runner(EMAIL, " "))) {
            assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                    .withMessageContaining("Set both APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD");
        }
        verifyNoInteractions(accounts, passwordEncoder);
    }

    @Test
    void refusesToStartWithValuesThatBreakTheRegistrationRules() {
        assertThatIllegalStateException().isThrownBy(() -> runner("not-an-email", "short").run(null))
                .withMessage("The bootstrap administrator is invalid: email must be a valid email address; "
                        + "password must be at least 12 characters long")
                // The error names the rule, never the value.
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("not-an-email", "short"));
        assertThatIllegalStateException().isThrownBy(() -> runner(EMAIL, "Administrator").run(null))
                .withMessageContaining("password must not equal the display name");
        assertThatIllegalStateException().isThrownBy(() -> runner(EMAIL, "Password1234").run(null))
                .withMessage("The bootstrap administrator is invalid: password must not be a commonly used password")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Password1234"));
        verifyNoInteractions(accounts, passwordEncoder);
    }

    @Test
    void createsTheAdministratorWithAHashedPasswordAndNeverLogsIt(CapturedOutput output) {
        when(accounts.findCredentials(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(accounts.createAdministrator(any())).thenReturn(profile());

        runner("  Admin@Example.com ", PASSWORD).run(null);

        ArgumentCaptor<NewAccount> account = ArgumentCaptor.forClass(NewAccount.class);
        verify(accounts).createAdministrator(account.capture());
        // Trimmed like a registration (UserAccountService lower-cases); only the hash is passed on.
        assertThat(account.getValue()).isEqualTo(new NewAccount("Admin@Example.com", HASH, "Administrator", true));
        verify(accounts, never()).createAccount(any());
        assertThat(output).contains("Created the bootstrap administrator account " + ID).doesNotContain(PASSWORD);
    }

    @Test
    void leavesAnExistingAdministratorUnchanged(CapturedOutput output) {
        when(accounts.findCredentials(EMAIL)).thenReturn(Optional.of(new UserCredentials(ID, HASH, UserRole.ADMIN, true)));

        runner(EMAIL, PASSWORD).run(null);
        runner(EMAIL, PASSWORD).run(null);

        verify(accounts, never()).createAdministrator(any());
        // An existing account costs no BCrypt work, and its password is not reset.
        verifyNoInteractions(passwordEncoder);
        assertThat(output).contains("The bootstrap administrator already exists; nothing was changed").doesNotContain(PASSWORD);
    }

    @Test
    void neverPromotesAnExistingPlayer(CapturedOutput output) {
        when(accounts.findCredentials(EMAIL)).thenReturn(Optional.of(new UserCredentials(ID, HASH, UserRole.PLAYER, true)));

        runner(EMAIL, PASSWORD).run(null);

        verify(accounts, never()).createAdministrator(any());
        assertThat(output).contains("is not an administrator. It was not changed: existing accounts are never promoted.");
    }

    @Test
    void anotherInstanceCreatingItAtTheSameMomentIsFine(CapturedOutput output) {
        when(accounts.findCredentials(EMAIL))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new UserCredentials(ID, HASH, UserRole.ADMIN, true)));
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(accounts.createAdministrator(any())).thenThrow(new EmailAlreadyRegisteredException());

        runner(EMAIL, PASSWORD).run(null);

        assertThat(output).contains("The bootstrap administrator already exists");
    }

    @Test
    void thePropertiesNeverShowThePassword() {
        assertThat(new BootstrapAdminProperties(EMAIL, PASSWORD)).hasToString(
                "BootstrapAdminProperties[email=<set>, password=<redacted>]");
        assertThat(new BootstrapAdminProperties(null, "")).hasToString(
                "BootstrapAdminProperties[email=<none>, password=<none>]");
    }

    private AdminBootstrapRunner runner(String email, String password) {
        return new AdminBootstrapRunner(new BootstrapAdminProperties(email, password), accounts, passwordEncoder, VALIDATOR);
    }

    private static UserProfileResponse profile() {
        Instant now = Instant.parse("2026-11-04T09:00:00Z");
        return new UserProfileResponse(ID, EMAIL, "Administrator", UserRole.ADMIN, List.of(), now, now);
    }
}
