package com.auditiq.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.auditiq.analytics.AnalyticsClient;
import com.auditiq.analytics.AnalyticsClient.StartRunCommand;
import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLog.Outcome;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.common.PageResponse;
import com.auditiq.pipeline.PipelineDtos.QualityReport;
import com.auditiq.pipeline.PipelineDtos.RunDetail;
import com.auditiq.pipeline.PipelineDtos.RunPipelineRequest;
import com.auditiq.pipeline.PipelineDtos.RunStarted;
import com.auditiq.pipeline.PipelineDtos.RunSummary;
import com.auditiq.pipeline.PipelineDtos.Source;
import com.auditiq.security.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pipeline runs are executed by the analytics service, which owns the writes to pipeline_runs and
 * data_quality_reports. This service triggers runs (with RBAC and auditing) and reads their results.
 */
@Service
public class PipelineService {

    private static final String SUMMARY_SELECT = """
            SELECT r.id, r.run_ref, r.status, r.trigger_type, r.triggered_by, r.source, r.started_at, r.completed_at,
                   r.duration_ms, q.total_records, q.valid_records, q.rejected_records, q.duplicate_records
            FROM pipeline_runs r LEFT JOIN data_quality_reports q ON q.pipeline_run_id = r.id
            """;

    private final JdbcTemplate jdbc;
    private final AnalyticsClient analytics;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public PipelineService(JdbcTemplate jdbc, AnalyticsClient analytics, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.analytics = analytics;
        this.audit = audit;
        this.mapper = mapper;
    }

    public RunStarted start(RunPipelineRequest request) {
        if (request.source() == Source.CSV && (request.folder() == null || request.folder().isBlank())) {
            throw new IllegalArgumentException("A folder is required when the source is CSV");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", request.source().name());
        if (request.seed() != null) details.put("seed", request.seed());
        if (request.transactionCount() != null) details.put("transactionCount", request.transactionCount());
        if (request.folder() != null) details.put("folder", request.folder());

        var command = new StartRunCommand(request.source().name(), CurrentUser.require().username(),
                request.source() == Source.GENERATED ? request.seed() : null,
                request.source() == Source.GENERATED ? request.transactionCount() : null,
                request.source() == Source.CSV ? request.folder() : null);
        try {
            var result = analytics.startPipelineRun(command);
            details.put("runRef", result.runRef());
            audit.record(AuditAction.PIPELINE_RUN_TRIGGERED, "PIPELINE_RUN", result.runId(), details);
            return new RunStarted(result.runId(), result.runRef(), result.status());
        } catch (RuntimeException ex) {
            details.put("error", ex.getMessage());
            audit.record(AuditAction.PIPELINE_RUN_TRIGGERED, "PIPELINE_RUN", null, Outcome.FAILURE, details);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<RunSummary> runs(int page, int size) {
        long total = jdbc.queryForObject("SELECT count(*) FROM pipeline_runs", Long.class);
        List<RunSummary> rows = jdbc.query(SUMMARY_SELECT + " ORDER BY r.started_at DESC, r.id DESC LIMIT ? OFFSET ?",
                SUMMARY_MAPPER, size, (long) page * size);
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public RunDetail run(long id) {
        List<RunSummary> summary = jdbc.query(SUMMARY_SELECT + " WHERE r.id = ?", SUMMARY_MAPPER, id);
        if (summary.isEmpty()) {
            throw new NotFoundException("Pipeline run " + id + " not found");
        }
        Map<String, Object> extra = jdbc.queryForMap(
                "SELECT parameters::text AS parameters, steps::text AS steps, error_message FROM pipeline_runs WHERE id = ?",
                id);
        return new RunDetail(summary.getFirst(), json(extra.get("parameters"), new TypeReference<>() { }),
                json(extra.get("steps"), new TypeReference<>() { }), (String) extra.get("error_message"),
                qualityReport(id).orElse(null));
    }

    /** The report for a given run, or for the most recent run that produced one. */
    @Transactional(readOnly = true)
    public QualityReport latestOrForRun(Long runId) {
        if (runId != null) {
            return qualityReport(runId).orElseThrow(
                    () -> new NotFoundException("No data quality report for pipeline run " + runId));
        }
        List<Long> latest = jdbc.queryForList("""
                SELECT q.pipeline_run_id FROM data_quality_reports q ORDER BY q.created_at DESC, q.id DESC LIMIT 1
                """, Long.class);
        if (latest.isEmpty()) {
            throw new NotFoundException("No data quality report exists yet. Run the pipeline first.");
        }
        return qualityReport(latest.getFirst()).orElseThrow();
    }

    public List<String> sources() {
        return analytics.pipelineSources();
    }

    private java.util.Optional<QualityReport> qualityReport(long runId) {
        return jdbc.query("""
                SELECT q.pipeline_run_id, r.run_ref, q.total_records, q.valid_records, q.rejected_records,
                       q.duplicate_records, q.missing_value_total, q.missing_values::text AS missing_values,
                       q.rejection_reasons::text AS rejection_reasons, q.processing_duration_ms, q.status, q.created_at
                FROM data_quality_reports q JOIN pipeline_runs r ON r.id = q.pipeline_run_id
                WHERE q.pipeline_run_id = ?
                """, (rs, i) -> {
            int total = rs.getInt("total_records");
            int valid = rs.getInt("valid_records");
            return new QualityReport(rs.getLong("pipeline_run_id"), rs.getString("run_ref"), total, valid,
                    rs.getInt("rejected_records"), rs.getInt("duplicate_records"), rs.getInt("missing_value_total"),
                    json(rs.getString("missing_values"), new TypeReference<>() { }),
                    json(rs.getString("rejection_reasons"), new TypeReference<>() { }),
                    rs.getLong("processing_duration_ms"), rs.getString("status"), instant(rs, "created_at"),
                    total == 0 ? 0 : Math.round(valid * 10_000.0 / total) / 100.0);
        }, runId).stream().findFirst();
    }

    private <T> T json(Object value, TypeReference<T> type) {
        try {
            return value == null ? null : mapper.readValue(value.toString(), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt JSON in pipeline tables", ex);
        }
    }

    private static final RowMapper<RunSummary> SUMMARY_MAPPER = (rs, i) -> new RunSummary(
            rs.getLong("id"), rs.getString("run_ref"), rs.getString("status"), rs.getString("trigger_type"),
            rs.getString("triggered_by"), rs.getString("source"), instant(rs, "started_at"),
            instant(rs, "completed_at"), rs.getObject("duration_ms", Long.class),
            rs.getObject("total_records", Integer.class), rs.getObject("valid_records", Integer.class),
            rs.getObject("rejected_records", Integer.class), rs.getObject("duplicate_records", Integer.class));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
