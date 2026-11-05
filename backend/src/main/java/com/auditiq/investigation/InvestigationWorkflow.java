package com.auditiq.investigation;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.auditiq.common.Exceptions.BusinessRuleException;

/**
 * Investigation state machine. Outcomes are recorded only by an authorised human when resolving; the AI agent (Phase
 * 7) can never change status, close an investigation or record an outcome.
 *
 * <pre>
 * NEW ─► UNDER_REVIEW ◄─► AWAITING_EVIDENCE
 *  │         ▲  │              │
 *  └──────► ESCALATED ◄────────┘
 *            UNDER_REVIEW / AWAITING_EVIDENCE / ESCALATED ─► RESOLVED (outcome + summary required)
 *            RESOLVED ─► UNDER_REVIEW (re-open, reason required)
 * </pre>
 */
public final class InvestigationWorkflow {

    public enum Status { NEW, UNDER_REVIEW, AWAITING_EVIDENCE, ESCALATED, RESOLVED }

    public enum Outcome { NO_EXCEPTION, CONTROL_WEAKNESS, POLICY_BREACH, DATA_QUALITY_ISSUE, REFERRED_FOR_FURTHER_REVIEW }

    static final int MIN_SUMMARY_LENGTH = 20;

    private static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.NEW, EnumSet.of(Status.UNDER_REVIEW, Status.AWAITING_EVIDENCE, Status.ESCALATED),
            Status.UNDER_REVIEW, EnumSet.of(Status.AWAITING_EVIDENCE, Status.ESCALATED, Status.RESOLVED),
            Status.AWAITING_EVIDENCE, EnumSet.of(Status.UNDER_REVIEW, Status.ESCALATED, Status.RESOLVED),
            Status.ESCALATED, EnumSet.of(Status.UNDER_REVIEW, Status.AWAITING_EVIDENCE, Status.RESOLVED),
            Status.RESOLVED, EnumSet.of(Status.UNDER_REVIEW));

    private InvestigationWorkflow() {
    }

    public static List<Status> allowedTransitions(Status from) {
        return List.copyOf(TRANSITIONS.get(from));
    }

    /** Validates a status change; throws {@link BusinessRuleException} (HTTP 422) explaining what is missing. */
    public static void validate(Status from, Status to, String reason, Outcome outcome, String outcomeSummary,
                                boolean assigned) {
        if (from == to) {
            throw new BusinessRuleException("The investigation is already " + label(to));
        }
        if (!TRANSITIONS.get(from).contains(to)) {
            throw new BusinessRuleException("An investigation cannot move from " + label(from) + " to " + label(to));
        }
        boolean hasReason = reason != null && !reason.isBlank();
        if (to == Status.ESCALATED && !hasReason) {
            throw new BusinessRuleException("A reason is required to escalate an investigation");
        }
        if (from == Status.RESOLVED && !hasReason) {
            throw new BusinessRuleException("A reason is required to re-open a resolved investigation");
        }
        if (to == Status.RESOLVED) {
            if (!assigned) {
                throw new BusinessRuleException("Assign the investigation to an auditor before recording an outcome");
            }
            if (outcome == null) {
                throw new BusinessRuleException("A human-reviewed outcome is required to resolve an investigation");
            }
            if (outcomeSummary == null || outcomeSummary.strip().length() < MIN_SUMMARY_LENGTH) {
                throw new BusinessRuleException("An outcome summary of at least " + MIN_SUMMARY_LENGTH
                        + " characters is required to resolve an investigation");
            }
        } else if (outcome != null || (outcomeSummary != null && !outcomeSummary.isBlank())) {
            throw new BusinessRuleException("An outcome can only be recorded when resolving an investigation");
        }
    }

    /** Exception status that mirrors the investigation status. */
    static String exceptionStatusFor(Status status) {
        return status == Status.RESOLVED ? "CLOSED" : "UNDER_INVESTIGATION";
    }

    private static String label(Status status) {
        return status.name().replace('_', ' ').toLowerCase();
    }
}
