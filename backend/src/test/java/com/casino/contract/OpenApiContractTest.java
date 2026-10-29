package com.casino.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.swagger.v3.oas.models.media.Schema;
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
                {"title":"Internal Server Error","status":500,"detail":"x","instance":"/api/v1/system/status","code":"X"}""";

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
}
