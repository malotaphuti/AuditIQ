package com.auditiq.ml;

import java.util.List;
import java.util.Map;

import com.auditiq.analytics.AnalyticsClient;
import com.auditiq.analytics.AnalyticsClient.TrainModelCommand;
import com.auditiq.audit.AuditLogRepository;
import com.auditiq.common.Exceptions.ConflictException;
import com.auditiq.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MlIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    AnalyticsClient analytics;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    private JsonNode node(String text) throws Exception {
        return json.readTree(text);
    }

    @BeforeEach
    void seed() {
        reset(analytics);
        jdbc.update("""
                INSERT INTO accounts (account_id, customer_id, account_type, status, opened_date)
                VALUES ('ACC-ML-001', 'CUS-ML-001', 'CHEQUE', 'ACTIVE', DATE '2020-01-01') ON CONFLICT DO NOTHING""");
        jdbc.update("""
                INSERT INTO transactions (transaction_id, account_id, customer_id, transaction_date, transaction_amount,
                    transaction_type, transaction_channel, account_type, transaction_status, approval_threshold,
                    approval_status)
                VALUES ('TXNML0000001', 'ACC-ML-001', 'CUS-ML-001', '2026-06-01T08:00:00Z', 90000, 'TRANSFER', 'ONLINE',
                        'CHEQUE', 'COMPLETED', 50000, 'NOT_REQUIRED') ON CONFLICT DO NOTHING""");
        jdbc.update("DELETE FROM audit_exceptions WHERE transaction_id = 'TXNML0000001'");
        jdbc.update("DELETE FROM anomaly_predictions WHERE transaction_id = 'TXNML0000001'");
        jdbc.update("DELETE FROM ml_model_versions WHERE version = 'IF-IT-1'");
        Long versionId = jdbc.queryForObject("""
                INSERT INTO ml_model_versions (model_name, version, algorithm, hyperparameters, feature_list,
                                               priority_thresholds, training_rows, metrics, is_active, trained_by)
                VALUES ('isolation_forest_it', 'IF-IT-1', 'IsolationForest', '{"n_estimators": 200}',
                        '[{"name": "log_amount"}]', '{"anomaly": 0.55}', 1, '{"metrics": {"rocAuc": 0.98}}', true,
                        'auditor') RETURNING id""", Long.class);
        Long predictionId = jdbc.queryForObject("""
                INSERT INTO anomaly_predictions (transaction_id, model_version_id, anomaly_score, is_anomaly, priority,
                                                 explanation)
                VALUES ('TXNML0000001', ?, 0.712345, true, 'HIGH', '{"factors": []}') RETURNING id""",
                Long.class, versionId);
        jdbc.update("""
                INSERT INTO audit_exceptions (exception_ref, detection_source, prediction_id, transaction_id, account_id,
                                              severity, risk_category, reason, details)
                VALUES ('EXC-ML-IT-1', 'ML', ?, 'TXNML0000001', 'ACC-ML-001', 'HIGH', 'BEHAVIOURAL_ANOMALY',
                        'Anomaly score 0.712 (High priority). It is not a probability of fraud.',
                        '{"anomalyScore": 0.712345, "factors": [{"description": "Amount is 1.8 times the threshold"}]}')
                """, predictionId);
    }

    @AfterEach
    void restoreSettings() {
        jdbc.update("UPDATE system_settings SET setting_value = '0.55' WHERE setting_key = 'ml.anomaly_threshold'");
        jdbc.update("UPDATE system_settings SET setting_value = '0.60' WHERE setting_key = 'ml.priority_threshold_medium'");
        jdbc.update("UPDATE system_settings SET setting_value = '0.65' WHERE setting_key = 'ml.priority_threshold_high'");
        jdbc.update("UPDATE system_settings SET setting_value = 'MEDIUM' WHERE setting_key = 'ml.exception_min_priority'");
    }

    @Test
    void auditorTrainsAModelAndTheRunIsAudited() throws Exception {
        when(analytics.trainModel(any())).thenReturn(node("""
                {"modelVersionId": 9, "version": "IF-1", "trainingRows": 111009, "durationMs": 9000,
                 "metrics": {"metrics": {"rocAuc": 0.987, "precision": 0.5, "recall": 0.84}}}"""));
        mvc.perform(post("/api/ml/train").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nEstimators\": 300, \"seed\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("IF-1"));
        verify(analytics).trainModel(new TrainModelCommand("auditor", 300, null, 7));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("ML_MODEL_TRAINED")).anySatisfy(l -> {
            assertThat(l.getEntityId()).isEqualTo("9");
            assertThat(l.getDetails()).containsEntry("version", "IF-1").containsEntry("rocAuc", 0.987);
        });
    }

    @Test
    void trainingValidationAndRbac() throws Exception {
        mvc.perform(post("/api/ml/train").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nEstimators\": 5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/ml/train").header("Authorization", managerToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ml/detect").header("Authorization", managerToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(analytics);
    }

    @Test
    void detectionIsAuditedAndConflictsAreReported() throws Exception {
        when(analytics.detectAnomalies("auditor")).thenReturn(node("""
                {"modelVersionId": 9, "version": "IF-1", "newlyScored": 0, "anomalies": 3285,
                 "exceptionsCreated": 1554, "byPriority": {"HIGH": 979, "MEDIUM": 575, "LOW": 1731}}"""));
        mvc.perform(post("/api/ml/detect").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptionsCreated").value(1554));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("ML_DETECTION_RUN"))
                .anySatisfy(l -> assertThat(l.getDetails()).containsEntry("exceptionsCreated", 1554));

        when(analytics.detectAnomalies("admin"))
                .thenThrow(new ConflictException("No anomaly model has been trained yet. Train a model first."));
        mvc.perform(post("/api/ml/detect").header("Authorization", adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No anomaly model has been trained yet. Train a model first."));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("ML_DETECTION_RUN"))
                .anySatisfy(l -> assertThat(l.getOutcome().name()).isEqualTo("FAILURE"));
    }

    @Test
    void performanceAndModelHistoryAreReadableByAllRoles() throws Exception {
        when(analytics.modelPerformance(eq(null))).thenReturn(node("{\"version\": \"IF-IT-1\", \"metrics\": {\"f1\": 0.62}}"));
        mvc.perform(get("/api/ml/model-performance").header("Authorization", managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.f1").value(0.62));
        mvc.perform(get("/api/ml/models").header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].version", hasItem("IF-IT-1")));
        mvc.perform(get("/api/ml/model-performance").param("versionId", "-1").header("Authorization", managerToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void administratorsConfigureOrderedThresholds() throws Exception {
        mvc.perform(get("/api/ml/settings").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomalyThreshold").value(0.55))
                .andExpect(jsonPath("$.exceptionMinPriority").value("MEDIUM"));

        Map<String, Object> good = Map.of("anomalyThreshold", 0.5, "mediumThreshold", 0.58, "highThreshold", 0.66,
                "exceptionMinPriority", "HIGH");
        mvc.perform(put("/api/ml/settings").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(good)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.highThreshold").value(0.66))
                .andExpect(jsonPath("$.exceptionMinPriority").value("HIGH"))
                .andExpect(jsonPath("$.updatedBy").value("admin"));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("ML_SETTINGS_UPDATED")).isNotEmpty();

        Map<String, Object> unordered = Map.of("anomalyThreshold", 0.7, "mediumThreshold", 0.6, "highThreshold", 0.65,
                "exceptionMinPriority", "LOW");
        mvc.perform(put("/api/ml/settings").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(unordered)))
                .andExpect(status().isUnprocessableEntity());
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("anomalyThreshold", 1.5, "mediumThreshold", 0.6, "highThreshold", 0.65,
                        "exceptionMinPriority", "LOW"),
                Map.of("anomalyThreshold", 0.5, "mediumThreshold", 0.6, "highThreshold", 0.65,
                        "exceptionMinPriority", "SEVERE"))) {
            mvc.perform(put("/api/ml/settings").header("Authorization", adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bad)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/ml/settings").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(good)))
                .andExpect(status().isForbidden());
    }

    @Test
    void scoresFromTheActiveModelAppearOnExceptionsAndTransactions() throws Exception {
        String token = auditorToken();
        mvc.perform(get("/api/exceptions").param("source", "ML").param("accountId", "ACC-ML-001")
                        .param("sort", "anomalyScore").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].exceptionRef").value("EXC-ML-IT-1"))
                .andExpect(jsonPath("$.content[0].ruleId").doesNotExist())
                .andExpect(jsonPath("$.content[0].anomalyScore").value(0.712345))
                .andExpect(jsonPath("$.content[0].anomalyPriority").value("HIGH"));
        mvc.perform(get("/api/transactions/TXNML0000001").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomaly.score").value(0.712345))
                .andExpect(jsonPath("$.anomaly.modelVersion").value("IF-IT-1"))
                .andExpect(jsonPath("$.anomaly.flagged").value(true));
    }
}
