package com.auditiq.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class PipelineDtos {

    private PipelineDtos() {
    }

    public enum Source { GENERATED, CSV }

    public record RunPipelineRequest(
            @NotNull Source source,
            @Min(0) @Max(2_147_483_647L) Integer seed,
            @Min(1_000) @Max(500_000) Integer transactionCount,
            @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "Folder must be 1-64 letters, digits, '-' or '_'")
            String folder) {
    }

    public record RunStarted(long runId, String runRef, String status) {
    }

    public record RunSummary(long id, String runRef, String status, String triggerType, String triggeredBy,
                             String source, Instant startedAt, Instant completedAt, Long durationMs,
                             Integer totalRecords, Integer validRecords, Integer rejectedRecords,
                             Integer duplicateRecords) {
    }

    public record QualityReport(long pipelineRunId, String runRef, int totalRecords, int validRecords,
                                int rejectedRecords, int duplicateRecords, int missingValueTotal,
                                Map<String, Object> missingValues, Map<String, Object> rejectionReasons,
                                long processingDurationMs, String status, Instant createdAt,
                                double validRatePercent) {
    }

    public record RunDetail(RunSummary run, Map<String, Object> parameters, List<Map<String, Object>> steps,
                            String errorMessage, QualityReport qualityReport) {
    }

    public record Sources(List<String> folders) {
    }
}
