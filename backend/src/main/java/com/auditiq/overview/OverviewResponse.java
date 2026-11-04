package com.auditiq.overview;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.auditiq.audit.AuditLogResponse;

/** All values are computed from the database at request time – nothing is hard-coded. */
public record OverviewResponse(
        Instant generatedAt,
        TransactionKpis transactions,
        ExceptionKpis exceptions,
        Map<String, Long> investigationsByStatus,
        PipelineHealth pipeline,
        List<TrendPoint> transactionTrend,
        List<CategoryCount> riskDistribution,
        List<RecentException> recentExceptions,
        List<AuditLogResponse> recentActivity) {

    public record TransactionKpis(long total, BigDecimal totalValue, Instant firstTransactionAt,
                                  Instant lastTransactionAt) {
    }

    public record ExceptionKpis(long total, long highPriority, long open, BigDecimal exceptionRatePercent,
                                Map<String, Long> bySeverity, Map<String, Long> bySource) {
    }

    public record PipelineHealth(long totalRuns, long successfulRuns, LastRun lastRun) {
    }

    public record LastRun(String runRef, String status, Instant startedAt, Long durationMs, Integer totalRecords,
                          Integer validRecords, Integer rejectedRecords) {
    }

    public record TrendPoint(LocalDate date, long count, BigDecimal value) {
    }

    public record CategoryCount(String category, long count) {
    }

    public record RecentException(Long id, String exceptionRef, String detectionSource, String ruleId,
                                  String transactionId, String accountId, String severity, String riskCategory,
                                  String status, Instant detectedAt) {
    }
}
