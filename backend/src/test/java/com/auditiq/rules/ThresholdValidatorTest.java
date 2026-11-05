package com.auditiq.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThresholdValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final JsonNode schema = node("""
            {"window": {"type": "integer", "min": 1, "max": 48, "label": "Time window (hours)"},
             "ratio": {"type": "number", "min": 0.1, "max": 0.99},
             "flag": {"type": "boolean"},
             "types": {"type": "list", "allowed": ["A", "B"]}}""");

    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void validThresholdsAreNormalised() {
        JsonNode clean = ThresholdValidator.validate(
                node("{\"window\": 24.0, \"ratio\": 0.6, \"flag\": true, \"types\": [\"A\", \"A\", \"B\"]}"), schema);
        assertThat(clean.get("window").isIntegralNumber()).isTrue();
        assertThat(clean.get("window").asInt()).isEqualTo(24);
        assertThat(clean.get("types")).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"window\": 0, \"ratio\": 0.6, \"flag\": true, \"types\": [\"A\"]}|Time window (hours) must be at least 1",
            "{\"window\": 2.5, \"ratio\": 0.6, \"flag\": true, \"types\": [\"A\"]}|must be a whole number",
            "{\"window\": \"24\", \"ratio\": 0.6, \"flag\": true, \"types\": [\"A\"]}|must be a number",
            "{\"window\": 24, \"ratio\": 1.5, \"flag\": true, \"types\": [\"A\"]}|ratio must be at most 0.99",
            "{\"window\": 24, \"ratio\": 0.6, \"flag\": \"yes\", \"types\": [\"A\"]}|true or false",
            "{\"window\": 24, \"ratio\": 0.6, \"flag\": true, \"types\": []}|at least one value",
            "{\"window\": 24, \"ratio\": 0.6, \"flag\": true, \"types\": [\"C\"]}|unsupported value",
            "{\"window\": 24, \"ratio\": 0.6, \"flag\": true, \"types\": [\"A\"], \"x\": 1}|Unknown threshold 'x'",
            "{\"window\": 24, \"ratio\": 0.6, \"types\": [\"A\"]}|Missing threshold 'flag'"})
    void invalidThresholdsAreRejected(String json, String message) {
        assertThatThrownBy(() -> ThresholdValidator.validate(node(json), schema))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
}
