package com.auditiq.investigation;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.auditiq.audit.AuditLogRepository;
import com.auditiq.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exceptions API and the full investigation workflow against the real schema. */
class InvestigationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String RESOLUTION = "Supporting invoice obtained; duplicate was reversed by the bank.";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    private long auditorId;
    private long managerId;
    private long adminId;

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO accounts (account_id, customer_id, account_type, status, branch, opened_date,
                                      last_activity_date)
                VALUES ('ACC-INV-001', 'CUS-INV-001', 'CHEQUE', 'ACTIVE', 'Durban', DATE '2020-01-01', DATE '2026-01-01')
                ON CONFLICT DO NOTHING""");
        String tx = """
                INSERT INTO transactions (transaction_id, account_id, customer_id, transaction_date, transaction_amount,
                    transaction_type, transaction_channel, account_type, transaction_status, approval_threshold,
                    approval_status)
                VALUES (?, 'ACC-INV-001', 'CUS-INV-001', ?::timestamptz, ?, 'TRANSFER', 'ONLINE', 'CHEQUE',
                        'COMPLETED', 50000, 'NOT_REQUIRED')
                ON CONFLICT DO NOTHING""";
        jdbc.update(tx, "TXNINV000001", "2026-05-10T08:00:00Z", 1500.00);
        jdbc.update(tx, "TXNINV000002", "2026-05-10T08:20:00Z", 1500.00);
        jdbc.update(tx, "TXNINV000003", "2026-05-12T09:00:00Z", 320.00);
        auditorId = userId("auditor");
        managerId = userId("manager");
        adminId = userId("admin");
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    /** A fresh exception on TXNINV000002 for a unique (test-scoped) rule/transaction pair. */
    private long newException(String severity) {
        int n = SEQ.incrementAndGet();
        String txId = "TXNINV9" + String.format("%05d", n);
        jdbc.update("""
                INSERT INTO transactions (transaction_id, account_id, customer_id, transaction_date, transaction_amount,
                    transaction_type, transaction_channel, account_type, transaction_status, approval_threshold,
                    approval_status)
                VALUES (?, 'ACC-INV-001', 'CUS-INV-001', '2026-05-10T08:20:00Z', 1500.00, 'TRANSFER', 'ONLINE',
                        'CHEQUE', 'COMPLETED', 50000, 'NOT_REQUIRED')""", txId);
        return jdbc.queryForObject("""
                INSERT INTO audit_exceptions (exception_ref, detection_source, rule_id, transaction_id, account_id,
                                              severity, risk_category, reason, details)
                VALUES (?, 'RULE', 'AR-001', ?, 'ACC-INV-001', ?, 'PAYMENT_INTEGRITY',
                        'Same account, amount and business date as transaction TXNINV000001.',
                        '{"matchedTransactionId": "TXNINV000001", "minutesApart": 20,
                          "relatedTransactionIds": ["TXNINV000001"]}')
                RETURNING id""", Long.class, "EXC-IT-" + n, txId, severity);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token, Object body) throws Exception {
        return mvc.perform(request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    @Test
    void exceptionsAreSearchableByAuditorsAndManagersButNotAdministrators() throws Exception {
        long id = newException("HIGH");
        mvc.perform(get("/api/exceptions").param("accountId", "acc-inv-001").param("severity", "HIGH")
                        .param("from", "2026-05-10").param("to", "2026-05-10").param("sort", "amount")
                        .header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem((int) id)))
                .andExpect(jsonPath("$.content[0].ruleName").value("Duplicate payments"))
                .andExpect(jsonPath("$.content[0].amount").value(1500.0));
        mvc.perform(get("/api/exceptions/summary").header("Authorization", managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bySeverity.HIGH").isNumber())
                .andExpect(jsonPath("$.byRule", hasSize(6)));
        mvc.perform(get("/api/exceptions").header("Authorization", adminToken())).andExpect(status().isForbidden());
        mvc.perform(get("/api/exceptions").param("severity", "SEVERE").header("Authorization", auditorToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/exceptions").param("sort", "reason; DROP").header("Authorization", auditorToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exceptionDetailShowsTheRuleEvidenceAndRelatedActivityAndIsAudited() throws Exception {
        long id = newException("MEDIUM");
        mvc.perform(get("/api/exceptions/{id}", id).header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exception.exceptionRef").value("EXC-IT-" + SEQ.get()))
                .andExpect(jsonPath("$.rule.detectionLogic").isNotEmpty())
                .andExpect(jsonPath("$.details.minutesApart").value(20))
                .andExpect(jsonPath("$.transaction.amount").value(1500.0))
                .andExpect(jsonPath("$.linkedTransactions[0].transaction.transactionId").value("TXNINV000001"))
                .andExpect(jsonPath("$.linkedTransactions[0].relation").value("LINKED_BY_RULE"))
                .andExpect(jsonPath("$.accountActivity[*].transaction.transactionId", hasItem("TXNINV000003")))
                .andExpect(jsonPath("$.investigations", hasSize(0)));
        assertThat(auditLogs.findTop100ByEntityTypeAndEntityIdOrderByOccurredAtDesc("AUDIT_EXCEPTION",
                String.valueOf(id))).anySatisfy(l -> assertThat(l.getAction()).isEqualTo("EXCEPTION_VIEWED"));
        mvc.perform(get("/api/exceptions/{id}", 99_999_999).header("Authorization", auditorToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void fullInvestigationLifecycleWithHumanReviewedOutcome() throws Exception {
        long exceptionId = newException("HIGH");
        String auditor = auditorToken();

        // 1. Open the case: NEW, exception under investigation, system evidence captured, history started.
        JsonNode created = read(send(post("/api/investigations"), auditor,
                Map.of("exceptionId", exceptionId, "note", "Opened from duplicate payment finding."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.investigation.status").value("NEW"))
                .andExpect(jsonPath("$.investigation.priority").value("HIGH"))
                .andExpect(jsonPath("$.investigation.title").value("Duplicate payments – TXNINV9" +
                        String.format("%05d", SEQ.get())))
                .andExpect(jsonPath("$.exception.status").value("UNDER_INVESTIGATION"))
                .andExpect(jsonPath("$.evidence[0].evidenceType").value("TRANSACTION"))
                .andExpect(jsonPath("$.evidence[1].evidenceType").value("RULE_RESULT"))
                .andExpect(jsonPath("$.evidence[1].aiGenerated").value(false))
                .andExpect(jsonPath("$.history[0].toStatus").value("NEW"))
                .andExpect(jsonPath("$.notes", hasSize(1)))
                .andExpect(jsonPath("$.version").value(0)));
        long id = created.at("/investigation/id").asLong();

        // A second case for the same exception is refused.
        send(post("/api/investigations"), auditor, Map.of("exceptionId", exceptionId))
                .andExpect(status().isConflict());

        // 2. Assign, add a note, start the review.
        send(put("/api/investigations/{id}/assignment", id), auditor, Map.of("assigneeId", auditorId, "version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.assignedTo.username").value("auditor"))
                .andExpect(jsonPath("$.version").value(1));
        send(post("/api/investigations/{id}/notes", id), auditor, Map.of("note", "Requested the payment instruction."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes[0].note").value("Requested the payment instruction."))
                .andExpect(jsonPath("$.notes[0].author.username").value("auditor"));
        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "UNDER_REVIEW", "version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.allowedTransitions", hasItem("RESOLVED")));

        // 3. Resolving needs an outcome and summary.
        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "RESOLVED", "version", 2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("A human-reviewed outcome is required to resolve an investigation"));
        send(put("/api/investigations/{id}/status", id), managerToken(), Map.of("status", "RESOLVED", "version", 2,
                "outcome", "CONTROL_WEAKNESS", "outcomeSummary", RESOLUTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.status").value("RESOLVED"))
                .andExpect(jsonPath("$.investigation.outcome").value("CONTROL_WEAKNESS"))
                .andExpect(jsonPath("$.outcomeSummary").value(RESOLUTION))
                .andExpect(jsonPath("$.reviewedBy.username").value("manager"))
                .andExpect(jsonPath("$.exception.status").value("CLOSED"))
                .andExpect(jsonPath("$.investigation.resolvedAt").isNotEmpty());

        // 4. A resolved case is locked until re-opened (with a reason).
        send(post("/api/investigations/{id}/notes", id), auditor, Map.of("note", "Late note"))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "UNDER_REVIEW", "version", 3))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "UNDER_REVIEW", "version", 3,
                "reason", "New evidence received from the branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.outcome").doesNotExist())
                .andExpect(jsonPath("$.exception.status").value("UNDER_INVESTIGATION"))
                .andExpect(jsonPath("$.history[0].reason")
                        .value("Re-opened (previous outcome: CONTROL_WEAKNESS). New evidence received from the branch"));

        // 5. Every step is in the audit trail, attributed to the person who did it.
        JsonNode detail = read(mvc.perform(get("/api/investigations/{id}", id).header("Authorization", auditor))
                .andExpect(status().isOk()));
        assertThat(detail.at("/history")).hasSize(4);
        var actions = new java.util.ArrayList<String>();
        detail.at("/activity").forEach(a -> actions.add(a.get("action").asText() + ":" + a.get("username").asText()));
        assertThat(actions).contains("INVESTIGATION_CREATED:auditor", "INVESTIGATION_ASSIGNED:auditor",
                "INVESTIGATION_NOTE_ADDED:auditor", "INVESTIGATION_STATUS_CHANGED:auditor",
                "INVESTIGATION_OUTCOME_RECORDED:manager", "INVESTIGATION_REOPENED:auditor");
    }

    @Test
    void staleVersionsAndIneligibleAssigneesAreRejected() throws Exception {
        long exceptionId = newException("LOW");
        String auditor = auditorToken();
        long id = read(send(post("/api/investigations"), auditor, Map.of("exceptionId", exceptionId,
                "assigneeId", managerId))).at("/investigation/id").asLong();

        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "UNDER_REVIEW", "version", 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The record was modified by another user. Reload and try again."));
        send(put("/api/investigations/{id}/assignment", id), auditor, Map.of("assigneeId", adminId, "version", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message")
                        .value("Investigations can only be assigned to active auditors or audit managers"));
        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "RESOLVED", "version", 0,
                "outcome", "NO_EXCEPTION", "outcomeSummary", RESOLUTION))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "ESCALATED", "version", 0))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/investigations/{id}/status", id), auditor, Map.of("status", "CLOSED", "version", 0))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listFiltersSummaryAndAccessControl() throws Exception {
        long exceptionId = newException("CRITICAL");
        String manager = managerToken();
        send(post("/api/investigations"), manager, Map.of("exceptionId", exceptionId, "assigneeId", managerId,
                "title", "Manager-owned case")).andExpect(status().isCreated());

        mvc.perform(get("/api/investigations").param("assignee", "me").param("status", "OPEN")
                        .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", hasItem("Manager-owned case")))
                .andExpect(jsonPath("$.content[0].assignedTo.username").value("manager"));
        mvc.perform(get("/api/investigations/summary").header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.NEW").isNumber())
                .andExpect(jsonPath("$.assignedToMe").isNumber());
        mvc.perform(get("/api/investigations/assignees").header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem("auditor")));

        mvc.perform(get("/api/investigations").header("Authorization", adminToken()))
                .andExpect(status().isForbidden());
        send(post("/api/investigations"), adminToken(), Map.of("exceptionId", exceptionId))
                .andExpect(status().isForbidden());
        send(post("/api/investigations"), manager, Map.of("title", "no exception"))
                .andExpect(status().isBadRequest());
        send(post("/api/investigations"), manager, Map.of("exceptionId", 99_999_999))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/investigations").param("assignee", "someone").header("Authorization", manager))
                .andExpect(status().isBadRequest());
    }
}
