package com.auditiq.investigation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.auditiq.audit.AuditLogResponse;
import com.auditiq.exceptions.ExceptionDtos.ExceptionRow;
import com.auditiq.investigation.InvestigationWorkflow.Outcome;
import com.auditiq.investigation.InvestigationWorkflow.Status;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class InvestigationDtos {

    private InvestigationDtos() {
    }

    public record UserRef(long id, String username, String fullName) {
    }

    public record InvestigationRow(long id, String investigationRef, String title, String status, String priority,
                                   long exceptionId, String exceptionRef, String ruleId, String ruleName,
                                   String transactionId, String accountId, BigDecimal amount, UserRef assignedTo,
                                   UserRef createdBy, String outcome, Instant createdAt, Instant updatedAt,
                                   Instant resolvedAt) {
    }

    public record Note(long id, UserRef author, String note, Instant createdAt) {
    }

    public record StatusChange(long id, String fromStatus, String toStatus, UserRef changedBy, String reason,
                               Instant changedAt) {
    }

    public record Evidence(long id, String evidenceType, String referenceId, String description, JsonNode content,
                           boolean aiGenerated, String humanReviewStatus, UserRef addedBy, Instant createdAt) {
    }

    public record InvestigationDetail(InvestigationRow investigation, int version, String outcomeSummary,
                                      UserRef reviewedBy, Instant reviewedAt, ExceptionRow exception,
                                      List<Note> notes, List<StatusChange> history, List<Evidence> evidence,
                                      List<AuditLogResponse> activity, List<Status> allowedTransitions,
                                      List<Outcome> outcomes) {
    }

    public record InvestigationSummary(Map<String, Long> byStatus, long open, long assignedToMe, long unassigned,
                                       long exceptionsAwaitingReview, Double averageResolutionHours) {
    }

    public record CreateInvestigationRequest(
            @NotNull @Positive Long exceptionId,
            @Size(max = 200) String title,
            @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String priority,
            @Positive Long assigneeId,
            @Size(max = 4000) String note) {
    }

    public record StatusChangeRequest(
            @NotNull Status status,
            @Size(max = 1000) String reason,
            Outcome outcome,
            @Size(max = 4000) String outcomeSummary,
            @NotNull @PositiveOrZero Integer version) {
    }

    public record AssignmentRequest(@Positive Long assigneeId, @NotNull @PositiveOrZero Integer version) {
    }

    public record NoteRequest(@NotBlank @Size(max = 4000) String note) {
    }
}
