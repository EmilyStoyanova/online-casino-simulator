package archfixture.auth.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/** Uses the Spring Security configuration API where it belongs (R9). */
public class AuthSecurityConfig {

    public Object configure(HttpSecurity http) {
        return http;
    }
}
