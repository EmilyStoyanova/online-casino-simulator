package com.casino.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password authentication for login: Spring Security's {@link DaoAuthenticationProvider} with
 * {@code AccountUserDetailsService} and the BCrypt {@code PasswordEncoder}. Separate from
 * {@link SecurityConfig}, so web test slices that import only the filter chain need no accounts.
 */
@Configuration(proxyBeanMethods = false)
public class AuthenticationConfig {

    /**
     * <ul>
     *   <li>Unknown email: the provider hashes a dummy password and reports bad credentials (it hides
     *       {@code UsernameNotFoundException}), so the time and result equal a wrong password.</li>
     *   <li>Account status (disabled, locked, …) is checked only <em>after</em> the password, so a
     *       disabled account is not recognisable by a faster answer either.</li>
     *   <li>The returned authentication has its credentials erased.</li>
     * </ul>
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService accounts, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setPreAuthenticationChecks(user -> {
        });
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return new ProviderManager(provider);
    }
}
