package com.auditiq.rules;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Validates rule thresholds against the schema stored with each rule ({@code audit_rules.threshold_schema}). The
 * analytics service applies the same rules before every execution, so an invalid value can never reach a query.
 */
final class ThresholdValidator {

    private ThresholdValidator() {
    }

    /** Returns a normalised copy of {@code thresholds}; throws {@link IllegalArgumentException} with a clear message. */
    static ObjectNode validate(JsonNode thresholds, JsonNode schema) {
        if (thresholds == null || !thresholds.isObject()) {
            throw new IllegalArgumentException("thresholds must be an object");
        }
        for (Iterator<String> it = thresholds.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!schema.has(key)) {
                throw new IllegalArgumentException("Unknown threshold '" + key + "'");
            }
        }
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = schema.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String key = field.getKey();
            JsonNode value = thresholds.get(key);
            if (value == null || value.isNull()) {
                throw new IllegalArgumentException("Missing threshold '" + key + "'");
            }
            clean.set(key, check(key, value, field.getValue()));
        }
        return clean;
    }

    private static JsonNode check(String key, JsonNode value, JsonNode spec) {
        String type = spec.path("type").asText();
        String label = spec.path("label").asText(key);
        switch (type) {
            case "boolean" -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException(label + " must be true or false");
                }
                return value;
            }
            case "number", "integer" -> {
                if (!value.isNumber()) {
                    throw new IllegalArgumentException(label + " must be a number");
                }
                double number = value.asDouble();
                if (type.equals("integer") && number != Math.rint(number)) {
                    throw new IllegalArgumentException(label + " must be a whole number");
                }
                if (spec.has("min") && number < spec.get("min").asDouble()) {
                    throw new IllegalArgumentException(label + " must be at least " + spec.get("min").asText());
                }
                if (spec.has("max") && number > spec.get("max").asDouble()) {
                    throw new IllegalArgumentException(label + " must be at most " + spec.get("max").asText());
                }
                return type.equals("integer") ? JsonNodeFactory.instance.numberNode((long) number)
                        : JsonNodeFactory.instance.numberNode(value.decimalValue());
            }
            case "list" -> {
                if (!value.isArray() || value.isEmpty()) {
                    throw new IllegalArgumentException(label + " must contain at least one value");
                }
                Set<String> allowed = new LinkedHashSet<>();
                spec.path("allowed").forEach(a -> allowed.add(a.asText()));
                Set<String> values = new LinkedHashSet<>();
                for (JsonNode item : value) {
                    if (!item.isTextual() || (!allowed.isEmpty() && !allowed.contains(item.asText()))) {
                        throw new IllegalArgumentException(label + " contains an unsupported value: " + item);
                    }
                    values.add(item.asText());
                }
                var array = JsonNodeFactory.instance.arrayNode();
                values.forEach(array::add);
                return array;
            }
            default -> throw new IllegalStateException("Unsupported threshold type '" + type + "' for " + key);
        }
    }
}
