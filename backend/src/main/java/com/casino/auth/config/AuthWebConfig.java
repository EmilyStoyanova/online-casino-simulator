package com.casino.auth.config;

import com.casino.auth.controller.RegistrationAttemptInterceptor;
import com.casino.auth.service.RegistrationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring MVC settings of the {@code auth} module: registration throttling before body validation. */
@Configuration(proxyBeanMethods = false)
public class AuthWebConfig implements WebMvcConfigurer {

    static final String REGISTER_PATH = "/api/v1/auth/register";

    private final ObjectProvider<RegistrationService> registrationService;

    public AuthWebConfig(ObjectProvider<RegistrationService> registrationService) {
        this.registrationService = registrationService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RegistrationAttemptInterceptor(registrationService)).addPathPatterns(REGISTER_PATH);
    }
}
