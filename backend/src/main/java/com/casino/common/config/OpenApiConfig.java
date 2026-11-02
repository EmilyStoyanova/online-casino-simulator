package com.casino.common.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.casino.common.exception.ProblemCode;
import com.casino.common.exception.ProblemDetailFactory;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI configuration.
 *
 * <p>The API is designed first: {@code src/main/resources/static/openapi.yaml} is the contract
 * and the source of truth, served at {@code /openapi.yaml} and shown first in Swagger UI
 * ({@code /swagger-ui.html}). The specification generated from the code ({@code /v3/api-docs})
 * is kept as the "implementation view" and is compared with the contract by
 * {@code OpenApiContractIT}, so the two cannot drift apart unnoticed.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String PROBLEM_DETAIL_SCHEMA = "ProblemDetail";
    static final String FIELD_ERROR_SCHEMA = "FieldError";

    @Bean
    OpenAPI casinoOpenApi(@Value("${app.version}") String version) {
        return new OpenAPI().info(new Info()
                .title("Online Casino Simulator API")
                .version(version)
                .description("""
                        Implementation view generated from the code. The contract is /openapi.yaml. \
                        The platform uses virtual currency only: there are no real-money deposits, \
                        withdrawals, purchases or cash-equivalent prizes.""")
                .license(new License().name("Proprietary — SAP mentorship project")));
    }

    /**
     * Replaces springdoc's reflection-based {@code ProblemDetail} schema, which wrongly exposes the
     * Java {@code getProperties()} map as a nested {@code properties} field, with the actual
     * RFC 9457 wire format: extension members ({@code code}, {@code errors}) are top-level
     * properties. The {@code code} enum is taken from {@link ProblemCode}, so a new code cannot be
     * added without updating the contract.
     */
    @Bean
    OpenApiCustomizer problemDetailSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null
                    || !openApi.getComponents().getSchemas().containsKey(PROBLEM_DETAIL_SCHEMA)) {
                return;
            }
            Schema<Object> code = typed("string");
            code.setEnum(Arrays.stream(ProblemCode.values()).map(value -> (Object) value.name()).toList());
            openApi.getComponents().addSchemas(PROBLEM_DETAIL_SCHEMA, typed("object")
                    .addProperty("type", typed("string").format("uri-reference"))
                    .addProperty("title", typed("string"))
                    .addProperty("status", typed("integer").format("int32"))
                    .addProperty("detail", typed("string"))
                    .addProperty("instance", typed("string").format("uri-reference"))
                    .addProperty(ProblemDetailFactory.CODE, code)
                    .addProperty(ProblemDetailFactory.ERRORS, typed("array")
                            .items(new Schema<>().$ref("#/components/schemas/" + FIELD_ERROR_SCHEMA)))
                    .required(List.of("title", "status", ProblemDetailFactory.CODE))
                    .additionalProperties(Boolean.TRUE));
            openApi.getComponents().addSchemas(FIELD_ERROR_SCHEMA, typed("object")
                    .addProperty("field", typed("string"))
                    .addProperty("message", typed("string"))
                    .required(List.of("field", "message")));
        };
    }

    /** Creates a schema whose type is rendered correctly for both OpenAPI 3.0 and 3.1 output. */
    private static Schema<Object> typed(String type) {
        Schema<Object> schema = new Schema<>();
        schema.setType(type);
        schema.setTypes(Set.of(type));
        return schema;
    }
}
