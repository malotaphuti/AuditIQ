package com.auditiq.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    public record TransactionFilter(String q, String accountId, String customerId, String merchantId, String type,
                                    String channel, String category, String status, String approvalStatus,
                                    LocalDate from, LocalDate to, BigDecimal minAmount, BigDecimal maxAmount) {
    }

    public record TransactionRow(String transactionId, String accountId, String customerId, Instant transactionDate,
                                 BigDecimal amount, String currency, String transactionType, String channel,
                                 String merchantId, String merchantName, String merchantCategory, String location,
                                 String accountType, String status, BigDecimal approvalThreshold,
                                 String approvalStatus) {
    }

    public record AccountInfo(String accountId, String customerId, String accountType, String status, String branch,
                              LocalDate openedDate, LocalDate lastActivityBeforeWindow, long transactionCount,
                              BigDecimal averageAmount, Instant firstTransactionAt, Instant lastTransactionAt) {
    }

    public record MerchantInfo(String merchantId, String merchantName, String category, String city,
                               boolean vendor) {
    }

    public record ExceptionRef(long id, String exceptionRef, String detectionSource, String ruleId, String severity,
                               String riskCategory, String status, Instant detectedAt) {
    }

    public record Lineage(Long pipelineRunId, String runRef, Instant loadedAt, BigDecimal originalAmount,
                          String originalCurrency) {
    }

    public record TransactionDetail(TransactionRow transaction, AccountInfo account, MerchantInfo merchant,
                                    Lineage lineage, List<ExceptionRef> exceptions, AnomalyScore anomaly) {
    }

    /** Score from the active anomaly model; measures unusualness, not fraud probability. */
    public record AnomalyScore(BigDecimal score, String priority, boolean flagged, String modelVersion) {
    }

    public record Bucket(String key, long count, BigDecimal value) {
    }

    public record Totals(long count, BigDecimal value, BigDecimal averageValue, long accounts, long merchants,
                         Instant firstTransactionAt, Instant lastTransactionAt) {
    }

    public record TransactionAnalytics(Totals totals, List<Bucket> monthly, List<Bucket> byCategory,
                                       List<Bucket> byChannel, List<Bucket> byType, List<Bucket> byStatus,
                                       List<Bucket> byLocation) {
    }
}
