package com.auditiq.rules;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class RuleDtos {

    private RuleDtos() {
    }

    public record AuditRule(String ruleId, String ruleName, String description, String detectionLogic,
                            String riskCategory, String severity, JsonNode thresholds, JsonNode thresholdSchema,
                            boolean enabled, int version, Instant lastExecutedAt, Instant updatedAt, String updatedBy,
                            long exceptionsTotal, long exceptionsOpen, Execution lastExecution) {
    }

    public record Execution(long id, String ruleId, String ruleName, String batchRef, String status,
                            Integer ruleVersion, Integer matchesTotal, int exceptionsCreated, String executedBy,
                            Instant startedAt, Instant completedAt, Long durationMs, String errorMessage,
                            JsonNode thresholdsUsed) {
    }

    public record UpdateRuleRequest(
            @NotNull JsonNode thresholds,
            @NotNull Boolean enabled,
            @NotNull @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity) {
    }

    public record ExecuteRulesRequest(
            @Size(min = 1, max = 20) List<@Pattern(regexp = "^AR-\\d{3}$") String> ruleIds) {
    }

    public record ExecuteRulesResponse(String batchRef, long durationMs, int exceptionsCreated, int matchesTotal,
                                       List<RuleOutcome> results) {
    }

    public record RuleOutcome(String ruleId, String ruleName, long executionId, String status, int matchesTotal,
                              int exceptionsCreated, long durationMs, String error) {
    }
}
