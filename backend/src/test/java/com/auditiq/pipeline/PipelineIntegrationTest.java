package com.auditiq.pipeline;

import java.util.List;
import java.util.Map;

import com.auditiq.analytics.AnalyticsClient;
import com.auditiq.analytics.AnalyticsClient.StartRunCommand;
import com.auditiq.analytics.AnalyticsClient.StartRunResult;
import com.auditiq.audit.AuditLogRepository;
import com.auditiq.common.Exceptions.ConflictException;
import com.auditiq.common.Exceptions.ServiceUnavailableException;
import com.auditiq.support.AbstractIntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PipelineIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    AnalyticsClient analytics;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    private long runId;

    @BeforeEach
    void seedRun() {
        reset(analytics);
        String ref = "RUN-IT-" + System.nanoTime();
        runId = jdbc.queryForObject("""
                INSERT INTO pipeline_runs (run_ref, status, trigger_type, triggered_by, source, parameters, steps,
                                           completed_at, duration_ms)
                VALUES (?, 'SUCCESS', 'MANUAL', 'auditor', 'synthetic generator (seed 1)', '{"seed": 1}',
                        '[{"name": "extract", "status": "SUCCESS", "durationMs": 10}]', now(), 1234)
                RETURNING id
                """, Long.class, ref);
        jdbc.update("""
                INSERT INTO data_quality_reports (pipeline_run_id, total_records, valid_records, rejected_records,
                    duplicate_records, missing_value_total, missing_values, rejection_reasons, processing_duration_ms,
                    status)
                VALUES (?, 1000, 980, 15, 5, 20, '{"account_id": 7}', '{"MISSING_ACCOUNT_ID": 7}', 1200, 'SUCCESS')
                """, runId);
    }

    private String body(Map<String, Object> map) throws Exception {
        return json.writeValueAsString(map);
    }

    @Test
    void auditorCanTriggerARunAndItIsAudited() throws Exception {
        when(analytics.startPipelineRun(any())).thenReturn(new StartRunResult(99, "RUN-X", "RUNNING"));

        mvc.perform(post("/api/pipeline/run").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("source", "GENERATED", "seed", 7, "transactionCount", 5000))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runRef").value("RUN-X"));

        var captor = ArgumentCaptor.forClass(StartRunCommand.class);
        verify(analytics).startPipelineRun(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new StartRunCommand("GENERATED", "auditor", 7, 5000, null));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("PIPELINE_RUN_TRIGGERED"))
                .anySatisfy(l -> {
                    assertThat(l.getEntityId()).isEqualTo("99");
                    assertThat(l.getDetails()).containsEntry("runRef", "RUN-X");
                });
    }

    @Test
    void auditManagerCannotTriggerRuns() throws Exception {
        mvc.perform(post("/api/pipeline/run").header("Authorization", managerToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("source", "GENERATED"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(analytics);
    }

    @Test
    void requestValidation() throws Exception {
        String token = adminToken();
        mvc.perform(post("/api/pipeline/run").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("source", "CSV"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A folder is required when the source is CSV"));
        mvc.perform(post("/api/pipeline/run").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("source", "GENERATED", "transactionCount", 10))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.transactionCount").exists());
        mvc.perform(post("/api/pipeline/run").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("source", "CSV", "folder", "../../etc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.folder").exists());
        mvc.perform(post("/api/pipeline/run").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("source", "FTP"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(analytics);
    }

    @Test
    void concurrentRunIsConflictAndFailureIsAudited() throws Exception {
        when(analytics.startPipelineRun(any())).thenThrow(new ConflictException("A pipeline run is already in progress"));
        mvc.perform(post("/api/pipeline/run").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("source", "GENERATED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A pipeline run is already in progress"));
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("PIPELINE_RUN_TRIGGERED"))
                .anySatisfy(l -> assertThat(l.getOutcome().name()).isEqualTo("FAILURE"));
    }

    @Test
    void analyticsServiceDownIs503() throws Exception {
        when(analytics.startPipelineRun(any())).thenThrow(new ServiceUnavailableException("down", null));
        mvc.perform(post("/api/pipeline/run").header("Authorization", auditorToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("source", "GENERATED"))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void runHistoryAndDetailAreReadableByAllRoles() throws Exception {
        mvc.perform(get("/api/pipeline/runs").header("Authorization", managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber());

        mvc.perform(get("/api/pipeline/runs/{id}", runId).header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("SUCCESS"))
                .andExpect(jsonPath("$.run.validRecords").value(980))
                .andExpect(jsonPath("$.parameters.seed").value(1))
                .andExpect(jsonPath("$.steps[0].name").value("extract"))
                .andExpect(jsonPath("$.qualityReport.rejectionReasons.MISSING_ACCOUNT_ID").value(7))
                .andExpect(jsonPath("$.qualityReport.validRatePercent").value(98.0));
    }

    @Test
    void qualityReportByRunAndLatest() throws Exception {
        String token = auditorToken();
        mvc.perform(get("/api/pipeline/quality-report").param("runId", String.valueOf(runId))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRecords").value(1000))
                .andExpect(jsonPath("$.duplicateRecords").value(5))
                .andExpect(jsonPath("$.missingValues.account_id").value(7));
        mvc.perform(get("/api/pipeline/quality-report").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineRunId").isNumber());
        mvc.perform(get("/api/pipeline/quality-report").param("runId", "999999").header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pipeline/runs/999999").header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void sourcesComeFromTheAnalyticsService() throws Exception {
        when(analytics.pipelineSources()).thenReturn(List.of("sample"));
        mvc.perform(get("/api/pipeline/sources").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[0]").value("sample"));
    }
}
