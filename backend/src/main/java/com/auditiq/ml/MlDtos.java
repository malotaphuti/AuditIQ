package com.auditiq.ml;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class MlDtos {

    private MlDtos() {
    }

    public record TrainRequest(
            @Min(50) @Max(1000) Integer nEstimators,
            @Min(64) @Max(4096) Integer maxSamples,
            @Min(0) @Max(Integer.MAX_VALUE) Integer seed) {
    }

    public record ModelVersion(long id, String version, String algorithm, Instant trainedAt, String trainedBy,
                               int trainingRows, JsonNode hyperparameters, JsonNode features,
                               JsonNode priorityThresholds, JsonNode metrics, boolean active, long anomalies,
                               long exceptions) {
    }

    public record MlSettings(BigDecimal anomalyThreshold, BigDecimal mediumThreshold, BigDecimal highThreshold,
                             String exceptionMinPriority, String updatedBy, Instant updatedAt) {
    }

    public record UpdateSettingsRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
            @Digits(integer = 1, fraction = 3) BigDecimal anomalyThreshold,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
            @Digits(integer = 1, fraction = 3) BigDecimal mediumThreshold,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
            @Digits(integer = 1, fraction = 3) BigDecimal highThreshold,
            @NotNull @Pattern(regexp = "LOW|MEDIUM|HIGH") String exceptionMinPriority) {
    }
}
