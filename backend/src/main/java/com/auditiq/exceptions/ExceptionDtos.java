package com.auditiq.exceptions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.auditiq.transaction.TransactionDtos.TransactionRow;
import com.fasterxml.jackson.databind.JsonNode;

public final class ExceptionDtos {

    private ExceptionDtos() {
    }

    public record ExceptionFilter(String q, String severity, String riskCategory, String ruleId, String source,
                                  String status, String accountId, LocalDate from, LocalDate to) {
    }

    public record ExceptionRow(long id, String exceptionRef, String detectionSource, String ruleId, String ruleName,
                               String transactionId, String accountId, Instant transactionDate, BigDecimal amount,
                               String transactionType, String severity, String riskCategory, String status,
                               String reason, Instant detectedAt, InvestigationLink investigation) {
    }

    public record InvestigationLink(long id, String investigationRef, String status, String assignedTo) {
    }

    public record RuleInfo(String ruleId, String ruleName, String description, String detectionLogic,
                           String riskCategory, int currentVersion) {
    }

    public record ExecutionInfo(long id, String batchRef, String executedBy, Instant startedAt) {
    }

    public record RelatedTransaction(TransactionRow transaction, String relation, List<String> exceptionRefs) {
    }

    public record ExceptionDetail(ExceptionRow exception, JsonNode details, RuleInfo rule, ExecutionInfo execution,
                                  TransactionRow transaction, List<RelatedTransaction> linkedTransactions,
                                  List<RelatedTransaction> accountActivity, List<ExceptionRow> otherFindings,
                                  List<InvestigationLink> investigations) {
    }

    public record Count(String key, String label, long count) {
    }

    public record ExceptionSummary(long total, long open, long underInvestigation, long closed,
                                   Map<String, Long> bySeverity, List<Count> byRiskCategory, List<Count> byRule,
                                   Map<String, Long> bySource) {
    }
}
