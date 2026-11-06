package com.auditiq.ml;

import java.util.List;

import com.auditiq.ml.MlDtos.MlSettings;
import com.auditiq.ml.MlDtos.ModelVersion;
import com.auditiq.ml.MlDtos.TrainRequest;
import com.auditiq.ml.MlDtos.UpdateSettingsRequest;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/ml")
@Tag(name = "Anomaly detection", description = "Isolation Forest scores measure unusualness, not fraud probability")
public class MlController {

    private final MlService service;

    public MlController(MlService service) {
        this.service = service;
    }

    @PostMapping("/train")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @Operation(summary = "Train a new model version on the curated data, evaluate it and make it active")
    public JsonNode train(@Valid @RequestBody(required = false) TrainRequest request) {
        return service.train(request);
    }

    @PostMapping("/detect")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @Operation(summary = "Score unscored transactions with the active model and raise potential exceptions")
    public JsonNode detect() {
        return service.detect();
    }

    @GetMapping("/model-performance")
    @Operation(summary = "Precision, recall, F1, confusion matrix, false-positive analysis and rule comparison")
    public JsonNode performance(@RequestParam(required = false) @Positive Long versionId) {
        return service.performance(versionId);
    }

    @GetMapping("/models")
    @Operation(summary = "Model version history")
    public List<ModelVersion> models() {
        return service.models();
    }

    @GetMapping("/settings")
    @Operation(summary = "Anomaly score thresholds and the minimum priority that raises an exception")
    public MlSettings settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change anomaly thresholds (applied on the next detection run; audited)")
    public MlSettings updateSettings(@Valid @RequestBody UpdateSettingsRequest request) {
        return service.updateSettings(request);
    }
}
