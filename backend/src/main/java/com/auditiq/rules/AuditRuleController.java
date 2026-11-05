package com.auditiq.rules;

import java.util.List;

import com.auditiq.common.PageResponse;
import com.auditiq.rules.RuleDtos.AuditRule;
import com.auditiq.rules.RuleDtos.Execution;
import com.auditiq.rules.RuleDtos.ExecuteRulesRequest;
import com.auditiq.rules.RuleDtos.ExecuteRulesResponse;
import com.auditiq.rules.RuleDtos.UpdateRuleRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/audit-rules")
@Tag(name = "Audit rules")
public class AuditRuleController {

    private static final String RULE_ID = "^AR-\\d{3}$";

    private final AuditRuleService service;

    public AuditRuleController(AuditRuleService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Audit rule catalogue with thresholds, last execution and exception counts")
    public List<AuditRule> list() {
        return service.list();
    }

    @GetMapping("/{ruleId}")
    @Operation(summary = "One audit rule")
    public AuditRule get(@PathVariable @Pattern(regexp = RULE_ID) String ruleId) {
        return service.get(ruleId);
    }

    @PutMapping("/{ruleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change a rule's thresholds, severity or enabled flag (validated against its schema; audited)")
    public AuditRule update(@PathVariable @Pattern(regexp = RULE_ID) String ruleId,
                            @Valid @RequestBody UpdateRuleRequest request) {
        return service.update(ruleId, request);
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @Operation(summary = "Run the selected rules (all enabled rules when ruleIds is omitted) against curated data")
    public ExecuteRulesResponse execute(@Valid @RequestBody(required = false) ExecuteRulesRequest request) {
        return service.execute(request == null ? null : request.ruleIds());
    }

    @GetMapping("/executions")
    @Operation(summary = "Rule execution history (newest first)")
    public PageResponse<Execution> executions(
            @RequestParam(required = false) @Pattern(regexp = RULE_ID) String ruleId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.executions(ruleId, page, size);
    }
}
