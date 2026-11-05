package archfixture.user.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/** Violates R9. */
public class SecurityOutsideAuth {

    public Object configure(HttpSecurity http) {
        return http;
    }
}
