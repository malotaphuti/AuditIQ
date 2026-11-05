package com.auditiq.rules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.auditiq.analytics.AnalyticsClient;
import com.auditiq.analytics.AnalyticsClient.ExecuteRulesCommand;
import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLog.Outcome;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.common.PageResponse;
import com.auditiq.rules.RuleDtos.AuditRule;
import com.auditiq.rules.RuleDtos.Execution;
import com.auditiq.rules.RuleDtos.ExecuteRulesResponse;
import com.auditiq.rules.RuleDtos.RuleOutcome;
import com.auditiq.rules.RuleDtos.UpdateRuleRequest;
import com.auditiq.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit rule catalogue and configuration. Detection itself runs in the analytics service (set-based SQL over the
 * curated layer); this service exposes the rules, validates configuration changes and triggers executions, auditing
 * each action.
 */
@Service
public class AuditRuleService {

    private static final String RULE_SELECT = """
            SELECT r.rule_id, r.rule_name, r.description, r.detection_logic, r.risk_category, r.severity,
                   r.thresholds, r.threshold_schema, r.enabled, r.version, r.last_executed_at, r.updated_at,
                   r.updated_by,
                   (SELECT count(*) FROM audit_exceptions e WHERE e.rule_id = r.rule_id) AS exceptions_total,
                   (SELECT count(*) FROM audit_exceptions e WHERE e.rule_id = r.rule_id AND e.status <> 'CLOSED')
                       AS exceptions_open
            FROM audit_rules r
            """;

    private static final String EXECUTION_SELECT = """
            SELECT x.id, x.rule_id, r.rule_name, x.batch_ref, x.status, x.rule_version, x.matches_total,
                   x.exceptions_found, x.executed_by, x.started_at, x.completed_at, x.duration_ms, x.error_message,
                   x.thresholds_used
            FROM rule_executions x JOIN audit_rules r ON r.rule_id = x.rule_id
            """;

    private final JdbcTemplate jdbc;
    private final AnalyticsClient analytics;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public AuditRuleService(JdbcTemplate jdbc, AnalyticsClient analytics, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.analytics = analytics;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AuditRule> list() {
        Map<String, Execution> latest = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (x.rule_id) x.id, x.rule_id, r.rule_name, x.batch_ref, x.status, x.rule_version,
                       x.matches_total, x.exceptions_found, x.executed_by, x.started_at, x.completed_at, x.duration_ms,
                       x.error_message, x.thresholds_used
                FROM rule_executions x JOIN audit_rules r ON r.rule_id = x.rule_id
                ORDER BY x.rule_id, x.started_at DESC, x.id DESC
                """, executionMapper()).forEach(e -> latest.put(e.ruleId(), e));
        return jdbc.query(RULE_SELECT + " ORDER BY r.rule_id",
                (rs, i) -> mapRule(rs, latest.get(rs.getString("rule_id"))));
    }

    @Transactional(readOnly = true)
    public AuditRule get(String ruleId) {
        Execution last = jdbc.query(EXECUTION_SELECT + " WHERE x.rule_id = ? ORDER BY x.started_at DESC, x.id DESC LIMIT 1",
                executionMapper(), ruleId).stream().findFirst().orElse(null);
        return jdbc.query(RULE_SELECT + " WHERE r.rule_id = ?", (rs, i) -> mapRule(rs, last), ruleId).stream()
                .findFirst().orElseThrow(() -> new NotFoundException("Audit rule " + ruleId + " not found"));
    }

    @Transactional
    public AuditRule update(String ruleId, UpdateRuleRequest request) {
        AuditRule current = get(ruleId);
        JsonNode clean = ThresholdValidator.validate(request.thresholds(), current.thresholdSchema());
        String username = CurrentUser.require().username();
        jdbc.update("""
                UPDATE audit_rules SET thresholds = ?::jsonb, enabled = ?, severity = ?, version = version + 1,
                                       updated_at = now(), updated_by = ?
                WHERE rule_id = ?
                """, write(clean), request.enabled(), request.severity(), username, ruleId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromVersion", current.version());
        details.put("toVersion", current.version() + 1);
        details.put("before", Map.of("thresholds", mapper.convertValue(current.thresholds(), Map.class), "enabled", current.enabled(),
                "severity", current.severity()));
        details.put("after", Map.of("thresholds", mapper.convertValue(clean, Map.class), "enabled", request.enabled(),
                "severity", request.severity()));
        audit.record(AuditAction.AUDIT_RULE_UPDATED, "AUDIT_RULE", ruleId, details);
        return get(ruleId);
    }

    public ExecuteRulesResponse execute(List<String> ruleIds) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ruleIds", ruleIds == null ? "ALL_ENABLED" : ruleIds);
        try {
            var result = analytics.executeRules(new ExecuteRulesCommand(CurrentUser.require().username(), ruleIds));
            List<RuleOutcome> outcomes = result.results().stream().map(r -> new RuleOutcome(r.ruleId(),
                    r.ruleName(), r.executionId(), r.status(), r.matchesTotal(), r.exceptionsCreated(),
                    r.durationMs(), r.error())).toList();
            int created = outcomes.stream().mapToInt(RuleOutcome::exceptionsCreated).sum();
            int matches = outcomes.stream().mapToInt(RuleOutcome::matchesTotal).sum();
            boolean anyFailed = outcomes.stream().anyMatch(o -> !"SUCCESS".equals(o.status()));
            details.put("batchRef", result.batchRef());
            details.put("exceptionsCreated", created);
            details.put("results", outcomes.stream().map(o -> Map.of("ruleId", o.ruleId(), "status", o.status(),
                    "matches", o.matchesTotal(), "created", o.exceptionsCreated())).toList());
            audit.record(AuditAction.AUDIT_RULES_EXECUTED, "RULE_BATCH", result.batchRef(),
                    anyFailed ? Outcome.FAILURE : Outcome.SUCCESS, details);
            return new ExecuteRulesResponse(result.batchRef(), result.durationMs(), created, matches, outcomes);
        } catch (RuntimeException ex) {
            details.put("error", ex.getMessage());
            audit.record(AuditAction.AUDIT_RULES_EXECUTED, "RULE_BATCH", null, Outcome.FAILURE, details);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Execution> executions(String ruleId, int page, int size) {
        String where = ruleId == null ? "" : " WHERE x.rule_id = ?";
        Object[] filter = ruleId == null ? new Object[0] : new Object[] {ruleId};
        Long total = jdbc.queryForObject("SELECT count(*) FROM rule_executions x" + where, Long.class, filter);
        Object[] args = ruleId == null ? new Object[] {size, (long) page * size}
                : new Object[] {ruleId, size, (long) page * size};
        List<Execution> rows = jdbc.query(EXECUTION_SELECT + where
                + " ORDER BY x.started_at DESC, x.id DESC LIMIT ? OFFSET ?", executionMapper(), args);
        long count = total == null ? 0 : total;
        return new PageResponse<>(rows, page, size, count, (int) Math.ceil((double) count / size));
    }

    // ---------------------------------------------------------------------------------------------------------------
    private AuditRule mapRule(ResultSet rs, Execution last) throws SQLException {
        return new AuditRule(rs.getString("rule_id"), rs.getString("rule_name"), rs.getString("description"),
                rs.getString("detection_logic"), rs.getString("risk_category"), rs.getString("severity"),
                read(rs.getString("thresholds")), read(rs.getString("threshold_schema")), rs.getBoolean("enabled"),
                rs.getInt("version"), instant(rs, "last_executed_at"), instant(rs, "updated_at"),
                rs.getString("updated_by"), rs.getLong("exceptions_total"), rs.getLong("exceptions_open"), last);
    }

    private RowMapper<Execution> executionMapper() {
        return (rs, i) -> new Execution(rs.getLong("id"), rs.getString("rule_id"), rs.getString("rule_name"),
                rs.getString("batch_ref"), rs.getString("status"), rs.getObject("rule_version", Integer.class),
                rs.getObject("matches_total", Integer.class), rs.getInt("exceptions_found"),
                rs.getString("executed_by"), instant(rs, "started_at"), instant(rs, "completed_at"),
                rs.getObject("duration_ms", Long.class), rs.getString("error_message"),
                read(rs.getString("thresholds_used")));
    }

    private JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored JSON is invalid", ex);
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
