package com.casino.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.casino.user.dto.UserCredentials;
import com.casino.user.dto.UserRole;
import com.casino.user.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class AccountUserDetailsServiceTest {

    private static final UUID ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");
    private static final String HASH = "{bcrypt}$2a$12$hash";

    private final UserAccountService accounts = mock(UserAccountService.class);
    private final AccountUserDetailsService service = new AccountUserDetailsService(accounts);

    @Test
    void theUsernameIsTheUserIdAndTheRoleIsTheAuthority() {
        when(accounts.findCredentials("alice@example.com")).thenReturn(Optional.of(new UserCredentials(ID, HASH, UserRole.ADMIN, true)));

        UserDetails user = service.loadUserByUsername("alice@example.com");

        assertThat(user.getUsername()).isEqualTo(ID.toString());
        assertThat(user.getPassword()).isEqualTo(HASH);
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void aDisabledAccountIsReturnedAsDisabled() {
        when(accounts.findCredentials("bob@example.com")).thenReturn(Optional.of(new UserCredentials(ID, HASH, UserRole.PLAYER, false)));

        assertThat(service.loadUserByUsername("bob@example.com").isEnabled()).isFalse();
    }

    @Test
    void anUnknownEmailIsNotFoundWithoutEchoingIt() {
        when(accounts.findCredentials("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("nobody");
    }

    @Test
    void credentialsNeverShowTheHash() {
        assertThat(new UserCredentials(ID, HASH, UserRole.PLAYER, true).toString()).doesNotContain(HASH, "bcrypt");
    }
}
