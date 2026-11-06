package com.auditiq.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Risk dashboard computed from the analytics layer (the same views Power BI uses). */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record Dashboard(Instant generatedAt, LocalDate from, LocalDate to, LocalDate dataFrom, LocalDate dataTo,
                            Executive executive, TransactionAnalysis transactions, RiskAnalysis risk,
                            InvestigationMonitoring investigations) {
    }

    public record Executive(long transactions, BigDecimal totalValue, long potentialExceptions, long highPriority,
                            long flaggedTransactions, BigDecimal exceptionRatePercent, long awaitingReview,
                            long openInvestigations, Pipeline pipeline) {
    }

    public record Pipeline(long runs, long successfulRuns, String lastStatus, Instant lastRunAt,
                           BigDecimal lastValidRatePercent) {
    }

    public record Bucket(String key, long count, BigDecimal value) {
    }

    public record DailyPoint(LocalDate date, long count, BigDecimal value, long flagged) {
    }

    public record TransactionAnalysis(List<DailyPoint> daily, List<Bucket> monthly, List<Bucket> byCategory,
                                      List<Bucket> byChannel, List<Bucket> byType, List<Bucket> byLocation,
                                      List<Bucket> byHour) {
    }

    public record DetectorCount(String key, String name, String type, long exceptions, long highPriority) {
    }

    public record RiskTrend(String month, long transactions, long flaggedTransactions, BigDecimal flaggedRatePercent,
                            long ruleFindings, long modelFindings) {
    }

    /** Flagged transactions by the detectors that flagged them. */
    public record DetectorOverlap(long rulesOnly, long modelOnly, long both) {
    }

    public record AccountRisk(String accountId, String accountType, String branch, long exceptions,
                              long ruleFindings, long modelFindings, long highPriority, long detectors,
                              BigDecimal flaggedAmount, BigDecimal maxAnomalyScore, long awaitingReview,
                              long openInvestigations) {
    }

    public record RiskAnalysis(Map<String, Long> bySeverity, List<Bucket> byRiskCategory,
                               List<DetectorCount> byDetector, Map<String, Long> byStatus,
                               List<RiskTrend> monthlyTrend, DetectorOverlap ruleVsModel,
                               List<AccountRisk> topAccounts) {
    }

    public record InvestigationMonitoring(Map<String, Long> byStatus, long open, long inProgress, long resolved,
                                          Double averageResolutionHours, Double oldestOpenAgeHours,
                                          long awaitingReview, List<Bucket> byOutcome, List<Bucket> openByAssignee) {
    }
}
