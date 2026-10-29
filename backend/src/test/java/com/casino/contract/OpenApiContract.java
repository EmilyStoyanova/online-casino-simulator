package com.casino.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * Test-side access to the design-first API contract ({@code static/openapi.yaml}).
 *
 * <p>Provides two checks without extra dependencies (uses the swagger-core models already
 * shipped with springdoc):
 * <ul>
 *   <li>{@link #differencesTo} — structural comparison with another specification, e.g. the one
 *       springdoc generates from the controllers;</li>
 *   <li>{@link #validateResponse} — validation of an actual HTTP response against the documented
 *       status code, media type and schema. Undocumented properties are reported unless the schema
 *       explicitly allows additional properties.</li>
 * </ul>
 */
public final class OpenApiContract {

    static final String CONTRACT_RESOURCE = "static/openapi.yaml";
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    private final OpenAPI spec;

    private OpenApiContract(OpenAPI spec) {
        this.spec = Objects.requireNonNull(spec, "Specification could not be parsed");
    }

    /** Loads the contract from the classpath. */
    public static OpenApiContract load() {
        try (InputStream in = OpenApiContract.class.getClassLoader().getResourceAsStream(CONTRACT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Contract not found on classpath: " + CONTRACT_RESOURCE);
            }
            return new OpenApiContract(Yaml31.mapper().readValue(in, OpenAPI.class));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static OpenApiContract fromYaml(String yaml) {
        return parse(() -> Yaml31.mapper().readValue(yaml, OpenAPI.class));
    }

    public static OpenApiContract fromJson(String json) {
        return parse(() -> Json31.mapper().readValue(json, OpenAPI.class));
    }

    public OpenAPI spec() {
        return spec;
    }

    // ------------------------------------------------------------------ structural comparison

    /**
     * Lists every structural difference between this specification and {@code other} for the
     * paths accepted by {@code pathFilter}. Descriptions, summaries and examples are ignored;
     * paths, methods, operation ids, tags, parameters, request bodies, status codes, media types
     * and schemas are compared.
     */
    public List<String> differencesTo(OpenApiContract other, Predicate<String> pathFilter) {
        List<String> diffs = new ArrayList<>();
        Map<String, PathItem> mine = paths(pathFilter);
        Map<String, PathItem> theirs = other.paths(pathFilter);
        compareKeys("paths", mine.keySet(), theirs.keySet(), diffs);

        for (String path : intersection(mine.keySet(), theirs.keySet())) {
            Map<PathItem.HttpMethod, Operation> myOps = mine.get(path).readOperationsMap();
            Map<PathItem.HttpMethod, Operation> theirOps = theirs.get(path).readOperationsMap();
            compareKeys(path + " methods", myOps.keySet(), theirOps.keySet(), diffs);

            for (PathItem.HttpMethod method : intersection(myOps.keySet(), theirOps.keySet())) {
                compareOperation(method + " " + path, myOps.get(method), other, theirOps.get(method), diffs);
            }
        }
        return diffs;
    }

    private void compareOperation(String loc, Operation mine, OpenApiContract other, Operation theirs, List<String> diffs) {
        compare(loc + " operationId", mine.getOperationId(), theirs.getOperationId(), diffs);
        compare(loc + " tags", asSet(mine.getTags()), asSet(theirs.getTags()), diffs);
        compare(loc + " parameters", parameterKeys(mine.getParameters()), parameterKeys(theirs.getParameters()), diffs);

        Content myBody = mine.getRequestBody() == null ? null : mine.getRequestBody().getContent();
        Content theirBody = theirs.getRequestBody() == null ? null : theirs.getRequestBody().getContent();
        compareContent(loc + " requestBody", myBody, other, theirBody, diffs);

        Map<String, ApiResponse> myResponses = orEmpty(mine.getResponses());
        Map<String, ApiResponse> theirResponses = orEmpty(theirs.getResponses());
        compareKeys(loc + " responses", myResponses.keySet(), theirResponses.keySet(), diffs);
        for (String code : intersection(myResponses.keySet(), theirResponses.keySet())) {
            compareContent(loc + " " + code, myResponses.get(code).getContent(),
                    other, theirResponses.get(code).getContent(), diffs);
        }
    }

    private void compareContent(String loc, Content mine, OpenApiContract other, Content theirs, List<String> diffs) {
        Map<String, MediaType> myTypes = orEmpty(mine);
        Map<String, MediaType> theirTypes = orEmpty(theirs);
        compareKeys(loc + " media types", myTypes.keySet(), theirTypes.keySet(), diffs);
        for (String type : intersection(myTypes.keySet(), theirTypes.keySet())) {
            compareSchemas(loc + " " + type, myTypes.get(type).getSchema(),
                    other, theirTypes.get(type).getSchema(), diffs, new HashSet<>());
        }
    }

    private void compareSchemas(String loc, Schema<?> mine, OpenApiContract other, Schema<?> theirs,
                                List<String> diffs, Set<String> visited) {
        if (mine == null || theirs == null) {
            if (mine != theirs) {
                diffs.add(loc + ": schema defined only in " + (mine == null ? "implementation" : "contract"));
            }
            return;
        }
        if (mine.get$ref() != null || theirs.get$ref() != null) {
            String myName = refName(mine.get$ref());
            String theirName = refName(theirs.get$ref());
            if (!Objects.equals(myName, theirName)) {
                diffs.add(loc + ": schema reference differs (contract: " + myName + ", implementation: " + theirName + ")");
            } else if (visited.add(myName)) {
                compareSchemas(SCHEMA_REF_PREFIX + myName, resolve(mine), other, other.resolve(theirs), diffs, visited);
            }
            return;
        }
        compare(loc + " type", types(mine), types(theirs), diffs);
        compare(loc + " format", mine.getFormat(), theirs.getFormat(), diffs);
        compare(loc + " enum", enumValues(mine), enumValues(theirs), diffs);
        compare(loc + " required", asSet(mine.getRequired()), asSet(theirs.getRequired()), diffs);
        compare(loc + " additionalProperties", allowsAdditionalProperties(mine), allowsAdditionalProperties(theirs), diffs);

        Map<String, Schema> myProps = orEmpty(mine.getProperties());
        Map<String, Schema> theirProps = orEmpty(theirs.getProperties());
        compareKeys(loc + " properties", myProps.keySet(), theirProps.keySet(), diffs);
        for (String name : intersection(myProps.keySet(), theirProps.keySet())) {
            compareSchemas(loc + "." + name, myProps.get(name), other, theirProps.get(name), diffs, visited);
        }
        if (mine.getItems() != null || theirs.getItems() != null) {
            compareSchemas(loc + "[]", mine.getItems(), other, theirs.getItems(), diffs, visited);
        }
    }

    // ------------------------------------------------------------------ response validation

    /**
     * Validates an HTTP response against the contract.
     *
     * @return violations; empty when the response conforms to the contract
     */
    public List<String> validateResponse(String path, String method, int status, String contentType, String body) {
        List<String> errors = new ArrayList<>();
        PathItem pathItem = spec.getPaths() == null ? null : spec.getPaths().get(path);
        Operation operation = pathItem == null ? null
                : pathItem.readOperationsMap().get(PathItem.HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)));
        if (operation == null) {
            return List.of(method + " " + path + " is not documented in the contract");
        }
        ApiResponse response = orEmpty(operation.getResponses()).get(String.valueOf(status));
        if (response == null) {
            return List.of(method + " " + path + ": status " + status + " is not documented");
        }
        String baseType = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        MediaType mediaType = orEmpty(response.getContent()).get(baseType);
        if (mediaType == null) {
            return List.of(method + " " + path + " " + status + ": media type '" + baseType + "' is not documented");
        }
        JsonNode node;
        try {
            node = Json31.mapper().readTree(body);
        } catch (IOException ex) {
            return List.of("Response body is not valid JSON: " + ex.getMessage());
        }
        validate("$", node, mediaType.getSchema(), errors);
        return errors;
    }

    private void validate(String loc, JsonNode node, Schema<?> declared, List<String> errors) {
        Schema<?> schema = resolve(declared);
        if (schema == null) {
            return;
        }
        Set<String> types = types(schema);
        if (node == null || node.isNull()) {
            if (!types.contains("null")) {
                errors.add(loc + ": null is not allowed");
            }
            return;
        }
        if (!types.isEmpty() && types.stream().noneMatch(type -> matchesType(type, node))) {
            errors.add(loc + ": expected type " + types + " but was " + node.getNodeType());
            return;
        }
        Set<String> allowed = enumValues(schema);
        if (!allowed.isEmpty() && !allowed.contains(node.asText())) {
            errors.add(loc + ": value '" + node.asText() + "' is not one of " + allowed);
        }
        validateFormat(loc, node, schema.getFormat(), errors);

        if (node.isObject()) {
            for (String required : orEmpty(schema.getRequired())) {
                if (!node.has(required)) {
                    errors.add(loc + ": missing required property '" + required + "'");
                }
            }
            Map<String, Schema> properties = orEmpty(schema.getProperties());
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                Schema<?> propertySchema = properties.get(field.getKey());
                if (propertySchema != null) {
                    validate(loc + "." + field.getKey(), field.getValue(), propertySchema, errors);
                } else if (!allowsAdditionalProperties(schema)) {
                    errors.add(loc + ": undocumented property '" + field.getKey() + "'");
                }
            }
        }
        if (node.isArray() && schema.getItems() != null) {
            for (int i = 0; i < node.size(); i++) {
                validate(loc + "[" + i + "]", node.get(i), schema.getItems(), errors);
            }
        }
    }

    private static void validateFormat(String loc, JsonNode node, String format, List<String> errors) {
        if (format == null) {
            return;
        }
        switch (format) {
            case "date-time" -> {
                try {
                    OffsetDateTime.parse(node.asText());
                } catch (DateTimeParseException ex) {
                    errors.add(loc + ": '" + node.asText() + "' is not a valid date-time");
                }
            }
            case "int32" -> {
                if (!node.canConvertToInt()) {
                    errors.add(loc + ": " + node + " does not fit into int32");
                }
            }
            case "uri", "uri-reference" -> {
                try {
                    URI.create(node.asText());
                } catch (IllegalArgumentException ex) {
                    errors.add(loc + ": '" + node.asText() + "' is not a valid " + format);
                }
            }
            default -> {
                // Other formats are informational only.
            }
        }
    }

    private static boolean matchesType(String type, JsonNode node) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> false;
        };
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, PathItem> paths(Predicate<String> filter) {
        Map<String, PathItem> result = new LinkedHashMap<>();
        orEmpty(spec.getPaths()).forEach((path, item) -> {
            if (filter.test(path)) {
                result.put(path, item);
            }
        });
        return result;
    }

    private Schema<?> resolve(Schema<?> schema) {
        Schema<?> current = schema;
        while (current != null && current.get$ref() != null) {
            String name = refName(current.get$ref());
            Map<String, Schema> schemas = spec.getComponents() == null ? Map.of() : orEmpty(spec.getComponents().getSchemas());
            current = schemas.get(name);
            if (current == null) {
                throw new IllegalStateException("Unresolvable schema reference: " + name);
            }
        }
        return current;
    }

    private static String refName(String ref) {
        return ref == null ? null : ref.substring(ref.lastIndexOf('/') + 1);
    }

    private static Set<String> types(Schema<?> schema) {
        Set<String> types = new TreeSet<>();
        if (schema.getTypes() != null) {
            types.addAll(schema.getTypes());
        }
        if (schema.getType() != null) {
            types.add(schema.getType());
        }
        if (Boolean.TRUE.equals(schema.getNullable())) {
            types.add("null");
        }
        return types;
    }

    private static Set<String> enumValues(Schema<?> schema) {
        return schema.getEnum() == null ? Set.of()
                : schema.getEnum().stream().map(String::valueOf).collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean allowsAdditionalProperties(Schema<?> schema) {
        Object additional = schema.getAdditionalProperties();
        return Boolean.TRUE.equals(additional) || additional instanceof Schema<?>;
    }

    private static Set<String> parameterKeys(List<Parameter> parameters) {
        return parameters == null ? Set.of() : parameters.stream()
                .map(p -> p.getIn() + ":" + p.getName() + (Boolean.TRUE.equals(p.getRequired()) ? ":required" : ""))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void compareKeys(String loc, Set<?> contract, Set<?> implementation, List<String> diffs) {
        Set<String> onlyContract = difference(contract, implementation);
        Set<String> onlyImplementation = difference(implementation, contract);
        if (!onlyContract.isEmpty()) {
            diffs.add(loc + ": only in contract " + onlyContract);
        }
        if (!onlyImplementation.isEmpty()) {
            diffs.add(loc + ": only in implementation " + onlyImplementation);
        }
    }

    private static void compare(String loc, Object contract, Object implementation, List<String> diffs) {
        if (!Objects.equals(contract, implementation)) {
            diffs.add(loc + ": contract " + contract + " vs implementation " + implementation);
        }
    }

    private static Set<String> difference(Collection<?> a, Collection<?> b) {
        return a.stream().filter(x -> !b.contains(x)).map(String::valueOf).collect(Collectors.toCollection(TreeSet::new));
    }

    private static <T> List<T> intersection(Set<T> a, Set<T> b) {
        return a.stream().filter(b::contains).toList();
    }

    private static Set<String> asSet(List<String> values) {
        return values == null ? Set.of() : new TreeSet<>(values);
    }

    private static <K, V> Map<K, V> orEmpty(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static OpenApiContract parse(IoSupplier<OpenAPI> parser) {
        try {
            return new OpenApiContract(parser.get());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
