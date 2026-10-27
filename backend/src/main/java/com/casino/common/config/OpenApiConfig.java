package com.casino.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. The interactive documentation is served at {@code /swagger-ui.html}
 * and the machine-readable specification at {@code /v3/api-docs}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI casinoOpenApi(@Value("${app.version}") String version) {
        return new OpenAPI().info(new Info()
                .title("Online Casino Simulator API")
                .version(version)
                .description("""
                        REST API of the Online Casino Simulator. The platform uses virtual \
                        currency only: there are no real-money deposits, withdrawals, purchases \
                        or cash-equivalent prizes.""")
                .license(new License().name("Proprietary — SAP mentorship project")));
    }
}
