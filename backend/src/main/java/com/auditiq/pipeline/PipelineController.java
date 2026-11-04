package com.auditiq.pipeline;

import com.auditiq.common.PageResponse;
import com.auditiq.pipeline.PipelineDtos.QualityReport;
import com.auditiq.pipeline.PipelineDtos.RunDetail;
import com.auditiq.pipeline.PipelineDtos.RunPipelineRequest;
import com.auditiq.pipeline.PipelineDtos.RunStarted;
import com.auditiq.pipeline.PipelineDtos.RunSummary;
import com.auditiq.pipeline.PipelineDtos.Sources;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/pipeline")
@Tag(name = "Data pipeline")
public class PipelineController {

    private final PipelineService service;

    public PipelineController(PipelineService service) {
        this.service = service;
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @Operation(summary = "Start an ingestion run (synthetic generator or a CSV folder). Runs asynchronously.")
    public RunStarted run(@Valid @RequestBody RunPipelineRequest request) {
        return service.start(request);
    }

    @GetMapping("/runs")
    @Operation(summary = "Pipeline execution history (newest first)")
    public PageResponse<RunSummary> runs(@RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.runs(page, size);
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Run detail: parameters, step log and data quality report")
    public RunDetail run(@PathVariable long id) {
        return service.run(id);
    }

    @GetMapping("/quality-report")
    @Operation(summary = "Data quality report for a run (latest when runId is omitted)")
    public QualityReport qualityReport(@RequestParam(required = false) Long runId) {
        return service.latestOrForRun(runId);
    }

    @GetMapping("/sources")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @Operation(summary = "CSV folders available for ingestion")
    public Sources sources() {
        return new Sources(service.sources());
    }
}
