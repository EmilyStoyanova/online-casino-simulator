package com.casino.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

/** Proves that the contract checks actually detect drift, so a green contract test means something. */
class OpenApiContractTest {

    private static final String STATUS_PATH = "/api/v1/system/status";
    private static final String JSON = "application/json";

    private static final String VALID_UP = """
            {"application":"online-casino-simulator","version":"0.1.0","status":"UP",
             "database":{"status":"UP","schemaVersion":"1"},"timestamp":"2026-10-29T10:15:30.123Z"}""";

    private final OpenApiContract contract = OpenApiContract.load();

    @Test
    void contractDocumentsTheSystemStatusOperation() {
        assertThat(contract.spec().getOpenapi()).isEqualTo("3.1.0");
        assertThat(contract.spec().getPaths()).containsKey(STATUS_PATH);
        assertThat(contract.spec().getPaths().get(STATUS_PATH).getGet().getOperationId()).isEqualTo("getSystemStatus");
    }

    @Test
    void acceptsAResponseThatMatchesTheContract() {
        assertThat(contract.validateResponse(STATUS_PATH, "GET", 200, JSON, VALID_UP)).isEmpty();
    }

    @Test
    void acceptsNullSchemaVersionWhenDown() {
        String down = """
                {"application":"a","version":"v","status":"DOWN",
                 "database":{"status":"DOWN","schemaVersion":null},"timestamp":"2026-10-29T10:15:30Z"}""";

        assertThat(contract.validateResponse(STATUS_PATH, "GET", 503, JSON, down)).isEmpty();
    }

    @Test
    void reportsMissingRequiredProperty() {
        String body = VALID_UP.replace("\"version\":\"0.1.0\",", "");

        assertThat(contract.validateResponse(STATUS_PATH, "GET", 200, JSON, body))
                .containsExactly("$: missing required property 'version'");
    }

    @Test
    void reportsUndocumentedProperty() {
        String body = VALID_UP.replace("\"version\":\"0.1.0\",", "\"version\":\"0.1.0\",\"debug\":true,");

        assertThat(contract.validateResponse(STATUS_PATH, "GET", 200, JSON, body))
                .containsExactly("$: undocumented property 'debug'");
    }

    @Test
    void reportsInvalidEnumTypeAndFormat() {
        String body = """
                {"application":"a","version":"v","status":"MAYBE",
                 "database":{"status":"UP","schemaVersion":1},"timestamp":"yesterday"}""";

        assertThat(contract.validateResponse(STATUS_PATH, "GET", 200, JSON, body)).containsExactlyInAnyOrder(
                "$.status: value 'MAYBE' is not one of [DOWN, UP]",
                "$.database.schemaVersion: expected type [null, string] but was NUMBER",
                "$.timestamp: 'yesterday' is not a valid date-time");
    }

    @Test
    void reportsUndocumentedStatusCodeAndMediaType() {
        assertThat(contract.validateResponse(STATUS_PATH, "GET", 418, JSON, VALID_UP))
                .containsExactly("GET " + STATUS_PATH + ": status 418 is not documented");
        assertThat(contract.validateResponse(STATUS_PATH, "GET", 200, "text/plain", VALID_UP))
                .containsExactly("GET " + STATUS_PATH + " 200: media type 'text/plain' is not documented");
    }

    @Test
    void problemDetailsAllowExtensionMembers() {
        String problem = """
                {"title":"Internal Server Error","status":500,"detail":"x","instance":"/api/v1/system/status","hint":"X"}""";

        assertThat(contract.validateResponse(STATUS_PATH, "GET", 500, "application/problem+json", problem)).isEmpty();
    }

    @Test
    void contractHasNoDifferencesToItself() {
        assertThat(contract.differencesTo(OpenApiContract.load(), path -> true)).isEmpty();
    }

    @Test
    void detectsStructuralDrift() {
        OpenApiContract drifted = OpenApiContract.load();
        Schema<?> systemStatus = drifted.spec().getComponents().getSchemas().get("SystemStatus");
        systemStatus.getProperties().remove("version");
        systemStatus.setRequired(List.of("application", "status", "database", "timestamp"));
        drifted.spec().getPaths().get(STATUS_PATH).getGet().setOperationId("status");
        drifted.spec().getPaths().get(STATUS_PATH).getGet().getResponses().remove("503");

        assertThat(contract.differencesTo(drifted, path -> path.startsWith("/api/"))).containsExactlyInAnyOrder(
                "GET " + STATUS_PATH + " operationId: contract getSystemStatus vs implementation status",
                "GET " + STATUS_PATH + " responses: only in contract [503]",
                "#/components/schemas/SystemStatus required: contract [application, database, status, timestamp, version]"
                        + " vs implementation [application, database, status, timestamp]",
                "#/components/schemas/SystemStatus properties: only in contract [version]");
    }

    @Test
    void detectsSecurityDriftIncludingGlobalRequirements() {
        OpenApiContract drifted = OpenApiContract.load();
        // No operation-level security: the global requirement applies.
        drifted.spec().addSecurityItem(new SecurityRequirement().addList("sessionCookie"));
        drifted.spec().getPaths().get(STATUS_PATH).getGet().setSecurity(null);

        assertThat(contract.differencesTo(drifted, path -> path.equals(STATUS_PATH))).containsExactly(
                "GET " + STATUS_PATH + " security: contract [] vs implementation [sessionCookie]");
    }

    // ------------------------------------------------------------------ planned elements

    @Test
    void week2OperationsArePlanned() {
        assertThat(contract.spec().getInfo().getVersion()).isEqualTo("0.2.0");
        assertThat(contract.plannedOperations()).containsExactlyInAnyOrder(
                "GET /api/v1/auth/csrf",
                "POST /api/v1/auth/register",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/logout",
                "GET /api/v1/users/me",
                "PATCH /api/v1/users/me",
                "GET /api/v1/interests");
        assertThat(contract.implementedOperations()).contains("GET " + STATUS_PATH).doesNotContainAnyElementsOf(
                contract.plannedOperations());
    }

    @Test
    void operationsFollowTheContractConventions() {
        contract.spec().getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            String name = method + " " + path;
            assertThat(operation.getSummary().endsWith(" (planned)"))
                    .as("%s: summary suffix '(planned)' if and only if marked planned", name)
                    .isEqualTo(contract.plannedOperations().contains(name));
            assertThat(operation.getSecurity())
                    .as("%s declares its security explicitly ([] = public)", name)
                    .isNotNull();
        }));
    }

    @Test
    void plannedElementsThatAreNotImplementedAreSkipped() {
        assertThat(contract.differencesTo(implementationWithoutPlannedElements(), path -> true)).isEmpty();
    }

    @Test
    void reportsPlannedOperationThatIsAlreadyImplemented() {
        OpenApiContract implementation = OpenApiContract.load();
        implementation.spec().getPaths().get("/api/v1/interests").getGet().getExtensions()
                .remove(OpenApiContract.IMPLEMENTATION_STATUS);

        assertThat(contract.differencesTo(implementation, path -> true)).containsExactly(
                "GET /api/v1/interests: implemented, but still marked x-implementation-status: planned");
    }

    @Test
    void reportsPlannedPropertyThatIsAlreadyImplemented() {
        OpenApiContract implementation = implementationWithoutPlannedElements();
        Schema<?> code = (Schema<?>) OpenApiContract.load().spec().getComponents().getSchemas().get("ProblemDetail")
                .getProperties().get("code");
        code.getExtensions().remove(OpenApiContract.IMPLEMENTATION_STATUS);
        implementation.spec().getComponents().getSchemas().get("ProblemDetail").addProperty("code", code);

        assertThat(contract.differencesTo(implementation, path -> true)).containsExactly(
                "#/components/schemas/ProblemDetail.code: implemented, but still marked x-implementation-status: planned");
    }

    @Test
    void plannedOperationsCannotBeValidatedAsImplemented() {
        assertThat(contract.validateResponse("/api/v1/interests", "GET", 200, JSON, "[]")).containsExactly(
                "GET /api/v1/interests is marked x-implementation-status: planned; remove the marker when implementing it");
    }

    @Test
    void reportsPlannedPropertiesInResponses() {
        String problem = """
                {"title":"Internal Server Error","status":500,"instance":"/api/v1/system/status","code":"INTERNAL_ERROR"}""";

        assertThat(contract.validateResponse(STATUS_PATH, "GET", 500, "application/problem+json", problem)).containsExactly(
                "$.code: property is marked x-implementation-status: planned but present; remove the marker when implementing it");
    }

    @Test
    void rejectsInvalidUseOfTheMarker() {
        assertThatIllegalStateException()
                .isThrownBy(() -> OpenApiContract.fromYaml(minimalSpec("x-implementation-status: done", "")))
                .withMessage("GET /a: unsupported x-implementation-status 'done' (allowed: 'planned', or no marker once implemented)");
        assertThatIllegalStateException()
                .isThrownBy(() -> OpenApiContract.fromYaml(minimalSpec("", """
                        required: [id]
                        properties:
                          id:
                            type: string
                            x-implementation-status: planned""")))
                .withMessage("#/components/schemas/Item.id: a planned property must not be required");
    }

    @Test
    void resolvesResponseReferences() {
        OpenApiContract referenced = OpenApiContract.fromYaml(minimalSpec("", ""));
        OpenApiContract inline = OpenApiContract.fromYaml(minimalSpec("", "").replace(
                "$ref: '#/components/responses/Ok'", "description: ok\n          content: {application/json: {schema: {type: string}}}"));

        assertThat(referenced.differencesTo(referenced, path -> true)).isEmpty();
        assertThat(referenced.validateResponse("/a", "GET", 200, JSON, "{}")).isEmpty();
        assertThat(referenced.differencesTo(inline, path -> true)).containsExactly(
                "GET /a 200 application/json: schema reference differs (contract: Item, implementation: null)");
    }

    /** The contract as springdoc would generate it while no planned element is implemented. */
    private static OpenApiContract implementationWithoutPlannedElements() {
        OpenApiContract implementation = OpenApiContract.load();
        implementation.spec().getPaths().values().forEach(item -> item.readOperationsMap().forEach((method, operation) -> {
            if (isPlanned(operation.getExtensions())) {
                item.operation(method, null);
            }
        }));
        implementation.spec().getPaths().values().removeIf(item -> item.readOperations().isEmpty());
        implementation.spec().getComponents().getSchemas().values().forEach(schema -> {
            if (schema.getProperties() != null) {
                schema.getProperties().values().removeIf(property -> isPlanned(((Schema<?>) property).getExtensions()));
            }
        });
        return implementation;
    }

    private static boolean isPlanned(Map<String, Object> extensions) {
        return extensions != null && OpenApiContract.PLANNED.equals(extensions.get(OpenApiContract.IMPLEMENTATION_STATUS));
    }

    /** One operation {@code GET /a} with a referenced response and schema {@code Item}. */
    private static String minimalSpec(String operationExtension, String itemSchema) {
        return """
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths:
                  /a:
                    get:
                      operationId: a
                      %s
                      responses:
                        '200':
                          $ref: '#/components/responses/Ok'
                components:
                  responses:
                    Ok:
                      description: ok
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Item'
                  schemas:
                    Item:
                      type: object
                %s
                """.formatted(operationExtension, itemSchema.indent(6).stripTrailing());
    }
}
