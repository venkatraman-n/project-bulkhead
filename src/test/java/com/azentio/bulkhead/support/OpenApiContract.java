package com.azentio.bulkhead.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal response validator for docs/api/openapi.yaml, using only Jackson (already on the classpath).
 * Checks what matters for a consumer contract: required fields present, no undeclared fields where
 * additionalProperties is false (so a renamed field fails), JSON types, enums and nested $refs.
 */
public final class OpenApiContract {

    private final JsonNode spec;

    private OpenApiContract(JsonNode spec) {
        this.spec = spec;
    }

    public static OpenApiContract load(Path file) {
        try {
            return new OpenApiContract(new ObjectMapper(new YAMLFactory()).readTree(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode spec() {
        return spec;
    }

    /** @return violations; empty means the body matches the documented response for path/method/status */
    public List<String> validateResponse(String pathTemplate, String method, int status, String contentType, String body) {
        JsonNode response = spec.path("paths").path(pathTemplate).path(method).path("responses").path(String.valueOf(status));
        if (response.isMissingNode()) {
            return List.of("status " + status + " is not documented for " + method.toUpperCase() + " " + pathTemplate);
        }
        response = resolve(response);
        JsonNode schema = response.path("content").path(contentType).path("schema");
        if (schema.isMissingNode()) {
            return List.of("content type " + contentType + " is not documented for status " + status);
        }
        try {
            List<String> errors = new ArrayList<>();
            validate(new ObjectMapper().readTree(body), schema, "$", errors);
            return errors;
        } catch (IOException e) {
            return List.of("body is not JSON: " + e.getMessage());
        }
    }

    /** Every $ref in the document points at something that exists. */
    public List<String> unresolvedRefs() {
        List<String> errors = new ArrayList<>();
        collectRefs(spec, errors);
        return errors;
    }

    private void validate(JsonNode value, JsonNode schema, String at, List<String> errors) {
        schema = resolve(schema);
        if (value.isNull()) {
            if (!schema.path("nullable").asBoolean(false)) {
                errors.add(at + " is null but not nullable");
            }
            return;
        }
        String type = schema.path("type").asText("");
        switch (type) {
            case "object" -> validateObject(value, schema, at, errors);
            case "array" -> {
                if (!value.isArray()) {
                    errors.add(at + " should be an array");
                    return;
                }
                for (int i = 0; i < value.size(); i++) {
                    validate(value.get(i), schema.path("items"), at + "[" + i + "]", errors);
                }
            }
            case "string" -> {
                if (!value.isTextual()) {
                    errors.add(at + " should be a string");
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    errors.add(at + " should be an integer");
                }
            }
            case "number" -> {
                if (!value.isNumber()) {
                    errors.add(at + " should be a number");
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    errors.add(at + " should be a boolean");
                }
            }
            default -> {
                // untyped schema: anything goes
            }
        }
        if (schema.has("enum") && value.isTextual()) {
            boolean allowed = false;
            for (JsonNode option : schema.path("enum")) {
                allowed |= option.asText().equals(value.asText());
            }
            if (!allowed) {
                errors.add(at + " value '" + value.asText() + "' is not in the documented enum");
            }
        }
    }

    private void validateObject(JsonNode value, JsonNode schema, String at, List<String> errors) {
        if (!value.isObject()) {
            errors.add(at + " should be an object");
            return;
        }
        JsonNode properties = schema.path("properties");
        for (JsonNode required : schema.path("required")) {
            if (!value.has(required.asText())) {
                errors.add(at + "." + required.asText() + " is required but missing");
            }
        }
        boolean closed = schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean(true);
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propertySchema = properties.path(field.getKey());
            if (propertySchema.isMissingNode()) {
                if (closed) {
                    errors.add(at + "." + field.getKey() + " is not declared in the contract");
                }
            } else {
                validate(field.getValue(), propertySchema, at + "." + field.getKey(), errors);
            }
        }
    }

    private JsonNode resolve(JsonNode node) {
        while (node.has("$ref")) {
            node = spec.at(node.path("$ref").asText().substring(1));
        }
        return node;
    }

    private void collectRefs(JsonNode node, List<String> errors) {
        if (node.has("$ref") && spec.at(node.path("$ref").asText().substring(1)).isMissingNode()) {
            errors.add("unresolved " + node.path("$ref").asText());
        }
        node.elements().forEachRemaining(child -> collectRefs(child, errors));
    }
}
