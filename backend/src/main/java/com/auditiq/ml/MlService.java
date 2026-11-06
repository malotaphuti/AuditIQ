package com.auditiq.ml;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.auditiq.analytics.AnalyticsClient;
import com.auditiq.analytics.AnalyticsClient.TrainModelCommand;
import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLog.Outcome;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.BusinessRuleException;
import com.auditiq.ml.MlDtos.MlSettings;
import com.auditiq.ml.MlDtos.ModelVersion;
import com.auditiq.ml.MlDtos.TrainRequest;
import com.auditiq.ml.MlDtos.UpdateSettingsRequest;
import com.auditiq.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anomaly model operations. Training, scoring and evaluation run in the analytics service; this service applies
 * RBAC, audits every run and owns the model's configurable thresholds.
 */
@Service
public class MlService {

    static final String ANOMALY = "ml.anomaly_threshold";
    static final String MEDIUM = "ml.priority_threshold_medium";
    static final String HIGH = "ml.priority_threshold_high";
    static final String MIN_PRIORITY = "ml.exception_min_priority";

    private final JdbcTemplate jdbc;
    private final AnalyticsClient analytics;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public MlService(JdbcTemplate jdbc, AnalyticsClient analytics, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.analytics = analytics;
        this.audit = audit;
        this.mapper = mapper;
    }

    public JsonNode train(TrainRequest request) {
        TrainRequest r = request == null ? new TrainRequest(null, null, null) : request;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nEstimators", r.nEstimators() == null ? 200 : r.nEstimators());
        details.put("maxSamples", r.maxSamples() == null ? 256 : r.maxSamples());
        details.put("seed", r.seed() == null ? 42 : r.seed());
        try {
            JsonNode result = analytics.trainModel(new TrainModelCommand(CurrentUser.require().username(),
                    r.nEstimators(), r.maxSamples(), r.seed()));
            details.put("version", result.path("version").asText());
            details.put("trainingRows", result.path("trainingRows").asLong());
            JsonNode m = result.path("metrics").path("metrics");
            if (!m.isMissingNode()) {
                details.put("rocAuc", m.path("rocAuc").asDouble());
                details.put("precision", m.path("precision").asDouble());
                details.put("recall", m.path("recall").asDouble());
            }
            audit.record(AuditAction.ML_MODEL_TRAINED, "ML_MODEL", result.path("modelVersionId").asLong(), details);
            return result;
        } catch (RuntimeException ex) {
            details.put("error", ex.getMessage());
            audit.record(AuditAction.ML_MODEL_TRAINED, "ML_MODEL", null, Outcome.FAILURE, details);
            throw ex;
        }
    }

    public JsonNode detect() {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            JsonNode result = analytics.detectAnomalies(CurrentUser.require().username());
            details.put("version", result.path("version").asText());
            details.put("newlyScored", result.path("newlyScored").asLong());
            details.put("anomalies", result.path("anomalies").asLong());
            details.put("exceptionsCreated", result.path("exceptionsCreated").asLong());
            audit.record(AuditAction.ML_DETECTION_RUN, "ML_MODEL", result.path("modelVersionId").asLong(), details);
            return result;
        } catch (RuntimeException ex) {
            details.put("error", ex.getMessage());
            audit.record(AuditAction.ML_DETECTION_RUN, "ML_MODEL", null, Outcome.FAILURE, details);
            throw ex;
        }
    }

    public JsonNode performance(Long versionId) {
        return analytics.modelPerformance(versionId);
    }

    @Transactional(readOnly = true)
    public List<ModelVersion> models() {
        return jdbc.query("""
                SELECT m.id, m.version, m.algorithm, m.trained_at, m.trained_by, m.training_rows,
                       m.hyperparameters::text AS hyper, m.feature_list::text AS features,
                       m.priority_thresholds::text AS thresholds, m.metrics::text AS metrics, m.is_active,
                       (SELECT count(*) FROM anomaly_predictions p WHERE p.model_version_id = m.id AND p.is_anomaly)
                           AS anomalies,
                       (SELECT count(*) FROM audit_exceptions e JOIN anomaly_predictions p ON p.id = e.prediction_id
                        WHERE p.model_version_id = m.id) AS exceptions
                FROM ml_model_versions m ORDER BY m.trained_at DESC, m.id DESC
                """, (rs, i) -> new ModelVersion(rs.getLong("id"), rs.getString("version"), rs.getString("algorithm"),
                rs.getTimestamp("trained_at").toInstant(), rs.getString("trained_by"), rs.getInt("training_rows"),
                read(rs.getString("hyper")), read(rs.getString("features")), read(rs.getString("thresholds")),
                read(rs.getString("metrics")), rs.getBoolean("is_active"), rs.getLong("anomalies"),
                rs.getLong("exceptions")));
    }

    @Transactional(readOnly = true)
    public MlSettings settings() {
        Map<String, String> values = new LinkedHashMap<>();
        String[] updatedBy = {null};
        Timestamp[] updatedAt = {null};
        jdbc.query("""
                SELECT setting_key, setting_value, updated_by, updated_at FROM system_settings
                WHERE setting_key IN (?, ?, ?, ?) ORDER BY updated_at
                """, rs -> {
                    values.put(rs.getString(1), rs.getString(2));
                    updatedBy[0] = rs.getString(3);
                    updatedAt[0] = rs.getTimestamp(4);
                }, ANOMALY, MEDIUM, HIGH, MIN_PRIORITY);
        return new MlSettings(new BigDecimal(values.getOrDefault(ANOMALY, "0.55")),
                new BigDecimal(values.getOrDefault(MEDIUM, "0.60")), new BigDecimal(values.getOrDefault(HIGH, "0.65")),
                values.getOrDefault(MIN_PRIORITY, "MEDIUM"), updatedBy[0],
                updatedAt[0] == null ? null : updatedAt[0].toInstant());
    }

    @Transactional
    public MlSettings updateSettings(UpdateSettingsRequest request) {
        if (request.anomalyThreshold().compareTo(request.mediumThreshold()) > 0
                || request.mediumThreshold().compareTo(request.highThreshold()) > 0) {
            throw new BusinessRuleException(
                    "Thresholds must be ordered: anomaly threshold ≤ medium threshold ≤ high threshold");
        }
        MlSettings before = settings();
        String user = CurrentUser.require().username();
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put(ANOMALY, request.anomalyThreshold().stripTrailingZeros().toPlainString());
        updates.put(MEDIUM, request.mediumThreshold().stripTrailingZeros().toPlainString());
        updates.put(HIGH, request.highThreshold().stripTrailingZeros().toPlainString());
        updates.put(MIN_PRIORITY, request.exceptionMinPriority());
        updates.forEach((key, value) -> jdbc.update("""
                UPDATE system_settings SET setting_value = ?, updated_by = ?, updated_at = now()
                WHERE setting_key = ?""", value, user, key));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", Map.of("anomaly", before.anomalyThreshold(), "medium", before.mediumThreshold(),
                "high", before.highThreshold(), "exceptionMinPriority", before.exceptionMinPriority()));
        details.put("after", Map.of("anomaly", request.anomalyThreshold(), "medium", request.mediumThreshold(),
                "high", request.highThreshold(), "exceptionMinPriority", request.exceptionMinPriority()));
        audit.record(AuditAction.ML_SETTINGS_UPDATED, "SYSTEM_SETTING", "ml", details);
        return settings();
    }

    private JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
