package com.casino.auth.service;

import com.casino.user.dto.UserCredentials;
import com.casino.user.service.UserAccountService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads an account for Spring Security's {@code DaoAuthenticationProvider} by email. The username of
 * the returned {@link User} is the <b>user id</b>, so the authenticated principal (and the session's
 * {@code PRINCIPAL_NAME}) is the id, never the email. A disabled account is returned as disabled and
 * rejected by the provider.
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final UserAccountService accounts;

    public AccountUserDetailsService(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        UserCredentials credentials = accounts.findCredentials(email)
                // The provider turns this into the same BadCredentialsException as a wrong password.
                .orElseThrow(() -> new UsernameNotFoundException("No account"));
        return User.withUsername(credentials.id().toString())
                .password(credentials.passwordHash())
                .roles(credentials.role().name())
                .disabled(!credentials.active())
                .build();
    }
}
