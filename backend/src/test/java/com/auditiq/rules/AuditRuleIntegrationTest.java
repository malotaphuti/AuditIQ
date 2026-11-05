package com.auditiq.rules;

import java.util.List;

import com.auditiq.analytics.AnalyticsClient;
import com.auditiq.analytics.AnalyticsClient.ExecuteRulesCommand;
import com.auditiq.analytics.AnalyticsClient.ExecuteRulesResult;
import com.auditiq.analytics.AnalyticsClient.RuleResult;
import com.auditiq.audit.AuditLogRepository;
import com.auditiq.common.Exceptions.ConflictException;
import com.auditiq.support.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditRuleIntegrationTest extends AbstractIntegrationTest {

    private static final String AR3_THRESHOLDS = """
            {"window_hours": 12, "min_transactions": 3, "near_threshold_ratio": 0.75,
             "transaction_types": ["TRANSFER", "VENDOR_PAYMENT"], "statuses": ["COMPLETED"]}""";

    @MockitoBean
    AnalyticsClient analytics;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    private String originalThresholds;

    @BeforeEach
    void remember() {
        reset(analytics);
        originalThresholds = jdbc.queryForObject("SELECT thresholds::text FROM audit_rules WHERE rule_id = 'AR-003'",
                String.class);
    }

    @AfterEach
    void restore() {
        jdbc.update("""
                UPDATE audit_rules SET thresholds = ?::jsonb, enabled = true, severity = 'HIGH', version = 1
                WHERE rule_id = 'AR-003'""", originalThresholds);
    }

    @Test
    void catalogueListsTheSixRulesWithTheirConfigurationForEveryRole() throws Exception {
        for (String token : List.of(adminToken(), auditorToken(), managerToken())) {
            mvc.perform(get("/api/audit-rules").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(6)))
                    .andExpect(jsonPath("$[0].ruleId").value("AR-001"))
                    .andExpect(jsonPath("$[0].ruleName").value("Duplicate payments"))
                    .andExpect(jsonPath("$[0].riskCategory").value("PAYMENT_INTEGRITY"))
                    .andExpect(jsonPath("$[0].detectionLogic").isNotEmpty())
                    .andExpect(jsonPath("$[0].thresholds.min_amount").value(100))
                    .andExpect(jsonPath("$[0].thresholdSchema.min_amount.type").value("number"))
                    .andExpect(jsonPath("$[5].ruleId").value("AR-006"));
        }
    }

    @Test
    void adminCanChangeThresholdsAndTheChangeIsVersionedAndAudited() throws Exception {
        String body = "{\"thresholds\": " + AR3_THRESHOLDS + ", \"enabled\": false, \"severity\": \"CRITICAL\"}";
        mvc.perform(put("/api/audit-rules/AR-003").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.thresholds.window_hours").value(12))
                .andExpect(jsonPath("$.updatedBy").value("admin"));

        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("AUDIT_RULE_UPDATED")).anySatisfy(l -> {
            assertThat(l.getEntityId()).isEqualTo("AR-003");
            assertThat(l.getDetails()).containsEntry("fromVersion", 1).containsEntry("toVersion", 2);
        });
    }

    @Test
    void invalidThresholdsAreRejectedWithAClearMessage() throws Exception {
        String token = adminToken();
        String tooSmall = AR3_THRESHOLDS.replace("\"window_hours\": 12", "\"window_hours\": 0");
        mvc.perform(put("/api/audit-rules/AR-003").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholds\": " + tooSmall + ", \"enabled\": true, \"severity\": \"HIGH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Time window (hours) must be at least 1"));
        String injected = AR3_THRESHOLDS.replace("\"TRANSFER\"", "\"TRANSFER'; DROP TABLE x;--\"");
        mvc.perform(put("/api/audit-rules/AR-003").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholds\": " + injected + ", \"enabled\": true, \"severity\": \"HIGH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/audit-rules/AR-003").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholds\": " + AR3_THRESHOLDS + ", \"enabled\": true, \"severity\": \"EXTREME\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/audit-rules/AR-999").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholds\": " + AR3_THRESHOLDS + ", \"enabled\": true, \"severity\": \"HIGH\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT version FROM audit_rules WHERE rule_id = 'AR-003'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void onlyAdministratorsChangeRules() throws Exception {
        for (String token : List.of(auditorToken(), managerToken())) {
            mvc.perform(put("/api/audit-rules/AR-003").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"thresholds\": " + AR3_THRESHOLDS + ", \"enabled\": true, \"severity\": \"HIGH\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void auditorExecutesRulesAndTheBatchIsAudited() throws Exception {
        when(analytics.executeRules(any())).thenReturn(new ExecuteRulesResult("RULES-1", "auditor", 900, List.of(
                new RuleResult("AR-001", "Duplicate payments", 11, "SUCCESS", 250, 240, 300, null),
                new RuleResult("AR-002", "Unusually large transactions", 12, "SUCCESS", 155, 155, 60, null))));

        mvc.perform(post("/api/audit-rules/execute").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ruleIds\": [\"AR-001\", \"AR-002\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchRef").value("RULES-1"))
                .andExpect(jsonPath("$.exceptionsCreated").value(395))
                .andExpect(jsonPath("$.matchesTotal").value(405))
                .andExpect(jsonPath("$.results", hasSize(2)));

        var captor = ArgumentCaptor.forClass(ExecuteRulesCommand.class);
        verify(analytics).executeRules(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ExecuteRulesCommand("auditor", List.of("AR-001", "AR-002")));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("AUDIT_RULES_EXECUTED")).anySatisfy(l -> {
            assertThat(l.getEntityId()).isEqualTo("RULES-1");
            assertThat(l.getOutcome().name()).isEqualTo("SUCCESS");
            assertThat(l.getDetails()).containsEntry("exceptionsCreated", 395);
        });
    }

    @Test
    void executingAllRulesSendsNoRuleList() throws Exception {
        when(analytics.executeRules(any())).thenReturn(new ExecuteRulesResult("RULES-2", "admin", 10, List.of()));
        mvc.perform(post("/api/audit-rules/execute").header("Authorization", adminToken()))
                .andExpect(status().isOk());
        verify(analytics).executeRules(new ExecuteRulesCommand("admin", null));
    }

    @Test
    void executionValidationRbacAndConflicts() throws Exception {
        mvc.perform(post("/api/audit-rules/execute").header("Authorization", managerToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/audit-rules/execute").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ruleIds\": [\"DROP TABLE\"]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(analytics);

        when(analytics.executeRules(any())).thenThrow(new ConflictException("Audit rules are already being executed"));
        mvc.perform(post("/api/audit-rules/execute").header("Authorization", auditorToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Audit rules are already being executed"));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("AUDIT_RULES_EXECUTED"))
                .anySatisfy(l -> assertThat(l.getOutcome().name()).isEqualTo("FAILURE"));
    }

    @Test
    void executionHistoryIsReadable() throws Exception {
        jdbc.update("""
                INSERT INTO rule_executions (rule_id, thresholds_used, status, exceptions_found, executed_by, batch_ref,
                                             rule_version, matches_total, completed_at, duration_ms)
                VALUES ('AR-004', '{"window_minutes": 60}', 'SUCCESS', 12, 'auditor', 'RULES-HIST', 1, 12, now(), 80)
                """);
        mvc.perform(get("/api/audit-rules/executions").param("ruleId", "AR-004")
                        .header("Authorization", managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].batchRef").value("RULES-HIST"))
                .andExpect(jsonPath("$.content[0].ruleName").value("High-frequency transactions"))
                .andExpect(jsonPath("$.content[0].thresholdsUsed.window_minutes").value(60));
        mvc.perform(get("/api/audit-rules").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[3].lastExecution.batchRef").value("RULES-HIST"));
    }
}
