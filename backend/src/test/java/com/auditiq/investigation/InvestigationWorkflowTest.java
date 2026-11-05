package com.auditiq.investigation;

import com.auditiq.common.Exceptions.BusinessRuleException;
import com.auditiq.investigation.InvestigationWorkflow.Outcome;
import com.auditiq.investigation.InvestigationWorkflow.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationWorkflowTest {

    private static final String SUMMARY = "Reviewed supporting documents; payment was approved retrospectively.";

    @ParameterizedTest
    @CsvSource({
            "NEW, UNDER_REVIEW", "NEW, AWAITING_EVIDENCE", "UNDER_REVIEW, AWAITING_EVIDENCE",
            "AWAITING_EVIDENCE, UNDER_REVIEW", "ESCALATED, UNDER_REVIEW", "ESCALATED, AWAITING_EVIDENCE"})
    void ordinaryTransitionsAreAllowed(Status from, Status to) {
        assertThatCode(() -> InvestigationWorkflow.validate(from, to, null, null, null, false))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"NEW, RESOLVED", "UNDER_REVIEW, NEW", "RESOLVED, ESCALATED", "RESOLVED, NEW"})
    void illegalTransitionsAreRejected(Status from, Status to) {
        assertThatThrownBy(() -> InvestigationWorkflow.validate(from, to, "reason", Outcome.NO_EXCEPTION, SUMMARY,
                true)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot move");
    }

    @Test
    void sameStatusIsRejected() {
        assertThatThrownBy(() -> InvestigationWorkflow.validate(Status.NEW, Status.NEW, null, null, null, false))
                .hasMessageContaining("already new");
    }

    @Test
    void escalationAndReopeningNeedAReason() {
        assertThatThrownBy(() -> InvestigationWorkflow.validate(Status.UNDER_REVIEW, Status.ESCALATED, " ", null,
                null, true)).hasMessageContaining("reason is required to escalate");
        assertThatThrownBy(() -> InvestigationWorkflow.validate(Status.RESOLVED, Status.UNDER_REVIEW, null, null,
                null, true)).hasMessageContaining("re-open");
        assertThatCode(() -> InvestigationWorkflow.validate(Status.RESOLVED, Status.UNDER_REVIEW,
                "New evidence received", null, null, true)).doesNotThrowAnyException();
    }

    @Test
    void resolvingRequiresAnAssigneeAnOutcomeAndASummary() {
        assertThatThrownBy(() -> InvestigationWorkflow.validate(Status.UNDER_REVIEW, Status.RESOLVED, null,
                Outcome.CONTROL_WEAKNESS, SUMMARY, false)).hasMessageContaining("Assign the investigation");
        assertThatThrownBy(() -> InvestigationWorkflow.validate(Status.UNDER_REVIEW, Status.RESOLVED, null, null,
                SUMMARY, true)).hasMessageContaining("human-reviewed outcome is required");
        assertThatThrownBy(() -> InvestigationWorkflow.validate(Status.UNDER_REVIEW, Status.RESOLVED, null,
                Outcome.CONTROL_WEAKNESS, "too short", true)).hasMessageContaining("at least 20 characters");
        assertThatCode(() -> InvestigationWorkflow.validate(Status.UNDER_REVIEW, Status.RESOLVED, null,
                Outcome.CONTROL_WEAKNESS, SUMMARY, true)).doesNotThrowAnyException();
    }

    @Test
    void anOutcomeCanOnlyBeRecordedWhenResolving() {
        assertThatThrownBy(() -> InvestigationWorkflow.validate(Status.NEW, Status.UNDER_REVIEW, null,
                Outcome.NO_EXCEPTION, null, true)).hasMessageContaining("only be recorded when resolving");
    }

    @Test
    void allowedTransitionsAndExceptionStatusMapping() {
        assertThat(InvestigationWorkflow.allowedTransitions(Status.NEW))
                .containsExactly(Status.UNDER_REVIEW, Status.AWAITING_EVIDENCE, Status.ESCALATED);
        assertThat(InvestigationWorkflow.allowedTransitions(Status.RESOLVED)).containsExactly(Status.UNDER_REVIEW);
        assertThat(InvestigationWorkflow.exceptionStatusFor(Status.RESOLVED)).isEqualTo("CLOSED");
        assertThat(InvestigationWorkflow.exceptionStatusFor(Status.ESCALATED)).isEqualTo("UNDER_INVESTIGATION");
    }
}
