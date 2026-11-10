package com.casino.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.casino.common.exception.ProblemCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

/** Proves that the contract checks actually detect drift, so a green contract test means something. */
class OpenApiContractTest {

    private static final String STATUS_PATH = "/api/v1/system/status";
    private static final String JSON = "application/json";
    private static final String WALLET_PATH = "/api/v1/wallet";
    private static final String WALLET_TRANSACTIONS_PATH = "/api/v1/wallet/transactions";

    private static final String VALID_UP = """
            {"application":"online-casino-simulator","version":"0.1.0","status":"UP",
             "database":{"status":"UP","schemaVersion":"1"},"timestamp":"2026-10-29T10:15:30.123Z"}""";

    /** Schema body for {@link #minimalSpec}: an optional property {@code extra} that is not implemented yet. */
    private static final String PLANNED_PROPERTY = """
            properties:
              extra:
                type: string
                x-implementation-status: planned""";

    /** Operation extension for {@link #minimalSpec}: {@code GET /a} is not implemented yet. */
    private static final String PLANNED_OPERATION = "x-implementation-status: planned";

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
    void responsesWithoutDocumentedContentMustHaveNoBody() {
        assertThat(contract.validateResponse("/api/v1/auth/csrf", "GET", 204, null, "")).isEmpty();
        assertThat(contract.validateResponse("/api/v1/auth/csrf", "GET", 204, JSON, "{}")).containsExactly(
                "GET /api/v1/auth/csrf 204: no content is documented, but the body is not empty");
    }

    @Test
    void problemDetailsAllowExtensionMembers() {
        String problem = """
                {"title":"Internal Server Error","status":500,"detail":"x","instance":"/api/v1/system/status",
                 "code":"INTERNAL_ERROR","hint":"X"}""";

        assertThat(contract.validateResponse(STATUS_PATH, "GET", 500, "application/problem+json", problem)).isEmpty();
    }

    @Test
    void problemDetailsRequireAKnownCode() {
        assertThat(contract.validateSchema("ProblemDetail", """
                {"title":"Bad Request","status":400,"code":"VALIDATION_FAILED","errors":[{"field":"email","message":"m"}]}"""))
                .isEmpty();
        assertThat(contract.validateSchema("ProblemDetail", """
                {"title":"Not Found","status":404}""")).containsExactly("$: missing required property 'code'");
        // The documented codes are exactly the ProblemCode constants.
        Set<String> codes = Arrays.stream(ProblemCode.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        assertThat(contract.validateSchema("ProblemDetail", """
                {"title":"I'm a teapot","status":418,"code":"TEAPOT","errors":[{"field":"email"}]}""")).containsExactly(
                "$.code: value 'TEAPOT' is not one of " + codes,
                "$.errors[0]: missing required property 'message'");
        assertThat(contract.validateSchema("Problem", "{}")).containsExactly("Schema Problem is not documented in the contract");
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
    void everyWeek2OperationIsImplementedAndTheWeek3WalletOperationsArePlanned() {
        assertThat(contract.spec().getInfo().getVersion()).isEqualTo("0.4.0");
        // GET /api/v1/auth/csrf (W2-04), POST /api/v1/auth/register (W2-07), login and logout (W2-08),
        // GET /api/v1/interests (W2-09), GET/PATCH /api/v1/users/me (W2-10).
        assertThat(contract.implementedOperations()).contains("GET " + STATUS_PATH, "GET /api/v1/users/me",
                "PATCH /api/v1/users/me");
        // Designed in W3-02; implemented in W3-07 and W3-08, which remove the marker.
        assertThat(contract.plannedOperations()).containsExactlyInAnyOrder(
                "GET " + WALLET_PATH, "GET " + WALLET_TRANSACTIONS_PATH);
    }

    // ------------------------------------------------------------------ wallet (Week 3)

    @Test
    void walletOperationsOnlyReadTheSessionUsersWalletAndRequireAPlayer() {
        Map<String, PathItem> walletPaths = contract.spec().getPaths().entrySet().stream()
                .filter(path -> path.getKey().startsWith(WALLET_PATH))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(walletPaths).containsOnlyKeys(WALLET_PATH, WALLET_TRANSACTIONS_PATH);

        walletPaths.forEach((path, item) -> {
            // Read-only: no operation that credits or debits a wallet (P-W3-9).
            assertThat(item.readOperationsMap()).as(path).containsOnlyKeys(PathItem.HttpMethod.GET);
            Operation operation = item.getGet();
            assertThat(operation.getRequestBody()).as(path).isNull();
            // The wallet is the session user's: no path parameter, no id in a query parameter.
            assertThat(path).doesNotContain("{");
            assertThat(Objects.requireNonNullElse(operation.getParameters(), List.<Parameter>of())).as(path)
                    .allSatisfy(parameter -> assertThat(parameter.getIn()).isEqualTo("query"))
                    .extracting(Parameter::getName).doesNotContain("userId", "walletId", "id");
            assertThat(operation.getSecurity()).as(path).containsExactly(new SecurityRequirement().addList("sessionCookie"));
            assertThat(operation.getResponses()).as(path).containsKeys("200", "401", "403", "500");
            assertThat(operation.getResponses().get("403").get$ref()).isEqualTo("#/components/responses/PlayerRoleRequired");
        });
        // Paging parameters of the history.
        assertThat(walletPaths.get(WALLET_TRANSACTIONS_PATH).getGet().getParameters())
                .extracting(Parameter::getName).containsExactly("limit", "cursor");
    }

    @Test
    void coinValuesAreJavaScriptSafeIntegers() {
        String max = "9007199254740991";
        assertThat(contract.validateSchema("Wallet", "{\"balance\":" + max + ",\"updatedAt\":\"2026-11-12T09:15:00Z\"}"))
                .isEmpty();
        assertThat(contract.validateSchema("Wallet", "{\"balance\":0,\"updatedAt\":\"2026-11-12T09:15:00Z\"}")).isEmpty();
        assertThat(contract.validateSchema("Wallet", """
                {"balance":-1,"updatedAt":"2026-11-12T09:15:00Z"}""")).containsExactly("$.balance: -1 is less than the minimum 0");
        assertThat(contract.validateSchema("Wallet", """
                {"balance":9007199254740992,"updatedAt":"2026-11-12T09:15:00Z"}""")).containsExactly(
                "$.balance: 9007199254740992 is greater than the maximum " + max);
        assertThat(contract.validateSchema("Wallet", """
                {"balance":10.5,"updatedAt":"2026-11-12T09:15:00Z"}""")).containsExactly(
                "$.balance: expected type [integer] but was NUMBER");
        assertThat(contract.validateSchema("Wallet", """
                {"balance":1000}""")).containsExactly("$: missing required property 'updatedAt'");
    }

    @Test
    void transactionPagesCarryLedgerEntriesAndAnOpaqueCursor() {
        String entry = """
                {"id":1,"type":"STARTING_BALANCE","amount":1000,"balanceAfter":1000,"createdAt":"2026-11-12T09:15:00Z"}""";
        assertThat(contract.validateSchema("WalletTransactionPage", "{\"items\":[" + entry + "],\"nextCursor\":null}")).isEmpty();
        assertThat(contract.validateSchema("WalletTransactionPage", "{\"items\":[],\"nextCursor\":\"djE6MQ\"}")).isEmpty();
        // nextCursor is required (null on the last page) and opaque, URL-safe Base64.
        assertThat(contract.validateSchema("WalletTransactionPage", "{\"items\":[]}"))
                .containsExactly("$: missing required property 'nextCursor'");
        assertThat(contract.validateSchema("WalletTransactionPage", "{\"items\":[],\"nextCursor\":\"a/b+c=\"}"))
                .containsExactly("$.nextCursor: 'a/b+c=' does not match ^[A-Za-z0-9_-]+$");
        assertThat(contract.validateSchema("WalletTransactionPage",
                "{\"items\":[" + String.join(",", Collections.nCopies(101, entry)) + "],\"nextCursor\":null}"))
                .containsExactly("$.items: more than 100 items");
        // Entries expose no idempotency key, wallet id or user id, and only documented types.
        assertThat(contract.validateSchema("WalletTransaction", """
                {"id":1,"type":"STARTING_BALANCE","amount":1000,"balanceAfter":1000,"createdAt":"2026-11-12T09:15:00Z",
                 "idempotencyKey":"starting-balance"}""")).containsExactly("$: undocumented property 'idempotencyKey'");
        assertThat(contract.validateSchema("WalletTransaction", """
                {"id":2,"type":"ADMIN_GIFT","amount":5,"balanceAfter":1005,"createdAt":"2026-11-12T09:15:00Z"}"""))
                .containsExactly("$.type: value 'ADMIN_GIFT' is not one of [STARTING_BALANCE]");
        assertThat(contract.validateSchema("WalletTransaction", """
                {"id":3,"type":"STARTING_BALANCE","amount":1000,"balanceAfter":-1,"createdAt":"2026-11-12T09:15:00Z"}"""))
                .containsExactly("$.balanceAfter: -1 is less than the minimum 0");
    }

    @Test
    void walletErrorCodesHaveTheirFixedStatus() {
        for (ProblemCode code : List.of(ProblemCode.INSUFFICIENT_FUNDS, ProblemCode.IDEMPOTENCY_KEY_REUSED,
                ProblemCode.BALANCE_LIMIT_EXCEEDED)) {
            assertThat(code.status().value()).as(code.name()).isEqualTo(409);
            assertThat(contract.validateSchema("ProblemDetail",
                    "{\"title\":\"Conflict\",\"status\":409,\"code\":\"" + code.name() + "\"}")).as(code.name()).isEmpty();
        }
    }

    @Test
    void valueConstraintsAreValidated() {
        String yaml = """
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths: {}
                components:
                  schemas:
                    Item:
                      type: object
                      properties:
                        count: {type: integer, format: int64, minimum: 1, maximum: 5}
                        small: {type: integer, format: int32}
                        code: {type: string, minLength: 2, maxLength: 3, pattern: '^[A-Z]+$'}
                        list: {type: array, minItems: 1, maxItems: 2, items: {type: string}}""";
        OpenApiContract constrained = OpenApiContract.fromYaml(yaml);

        assertThat(constrained.validateSchema("Item", """
                {"count":5,"small":7,"code":"ÄB","list":["a","b"]}""")).containsExactly(
                "$.code: 'ÄB' does not match ^[A-Z]+$");
        assertThat(constrained.validateSchema("Item", """
                {"count":0,"small":2147483648,"code":"A","list":[]}""")).containsExactly(
                "$.count: 0 is less than the minimum 1",
                "$.small: 2147483648 does not fit into int32",
                "$.code: shorter than 2 characters",
                "$.list: fewer than 1 items");
        assertThat(constrained.validateSchema("Item", """
                {"count":99999999999999999999,"code":"ABCD","list":["a","b","c"]}""")).containsExactly(
                "$.count: 99999999999999999999 does not fit into int64",
                "$.count: 99999999999999999999 is greater than the maximum 5",
                "$.code: longer than 3 characters",
                "$.list: more than 2 items");
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
        String yaml = minimalSpec(PLANNED_OPERATION, PLANNED_PROPERTY);
        OpenApiContract planned = OpenApiContract.fromYaml(yaml);

        assertThat(planned.plannedOperations()).containsExactly("GET /a");
        assertThat(planned.implementedOperations()).isEmpty();
        assertThat(planned.differencesTo(withoutPlannedElements(yaml), path -> true)).isEmpty();
    }

    @Test
    void reportsPlannedOperationThatIsAlreadyImplemented() {
        OpenApiContract planned = OpenApiContract.fromYaml(minimalSpec(PLANNED_OPERATION, ""));
        OpenApiContract implementation = OpenApiContract.fromYaml(minimalSpec("", ""));

        assertThat(planned.differencesTo(implementation, path -> true)).containsExactly(
                "GET /a: implemented, but still marked x-implementation-status: planned");
    }

    @Test
    void plannedPropertiesAreSkippedUntilImplemented() {
        OpenApiContract planned = OpenApiContract.fromYaml(minimalSpec("", PLANNED_PROPERTY));
        OpenApiContract notImplemented = OpenApiContract.fromYaml(minimalSpec("", ""));
        OpenApiContract implemented = OpenApiContract.fromYaml(minimalSpec("", PLANNED_PROPERTY.replace(
                "\n    x-implementation-status: planned", "")));

        assertThat(planned.differencesTo(notImplemented, path -> true)).isEmpty();
        assertThat(planned.differencesTo(implemented, path -> true)).containsExactly(
                "#/components/schemas/Item.extra: implemented, but still marked x-implementation-status: planned");
    }

    @Test
    void plannedOperationsCannotBeValidatedAsImplemented() {
        OpenApiContract planned = OpenApiContract.fromYaml(minimalSpec(PLANNED_OPERATION, ""));

        assertThat(planned.validateResponse("/a", "GET", 200, JSON, "{}")).containsExactly(
                "GET /a is marked x-implementation-status: planned; remove the marker when implementing it");
    }

    @Test
    void reportsPlannedPropertiesInResponses() {
        OpenApiContract planned = OpenApiContract.fromYaml(minimalSpec("", PLANNED_PROPERTY));

        assertThat(planned.validateResponse("/a", "GET", 200, JSON, "{}")).isEmpty();
        assertThat(planned.validateResponse("/a", "GET", 200, JSON, "{\"extra\":\"x\"}")).containsExactly(
                "$.extra: property is marked x-implementation-status: planned but present; remove the marker when implementing it");
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

    /** The specification {@code yaml} as springdoc would generate it while no planned element is implemented. */
    private static OpenApiContract withoutPlannedElements(String yaml) {
        OpenApiContract implementation = OpenApiContract.fromYaml(yaml);
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
