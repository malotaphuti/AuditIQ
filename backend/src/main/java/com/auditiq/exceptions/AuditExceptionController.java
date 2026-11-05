package com.auditiq.exceptions;

import java.time.LocalDate;

import com.auditiq.common.PageResponse;
import com.auditiq.exceptions.ExceptionDtos.ExceptionDetail;
import com.auditiq.exceptions.ExceptionDtos.ExceptionFilter;
import com.auditiq.exceptions.ExceptionDtos.ExceptionRow;
import com.auditiq.exceptions.ExceptionDtos.ExceptionSummary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/exceptions")
@PreAuthorize("hasAnyRole('AUDITOR','AUDIT_MANAGER')")
@Tag(name = "Audit exceptions", description = "Potential exceptions for human review – not confirmed wrongdoing")
public class AuditExceptionController {

    private static final String CODE = "^[A-Za-z0-9_ -]{1,40}$";

    private final AuditExceptionService service;

    public AuditExceptionController(AuditExceptionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search potential exceptions (filter by severity, date, account, category, rule, status)")
    public PageResponse<ExceptionRow> search(
            @RequestParam(required = false) @Size(max = 40) @Pattern(regexp = CODE) String q,
            @RequestParam(required = false) @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity,
            @RequestParam(required = false) @Pattern(regexp = CODE) String riskCategory,
            @RequestParam(required = false) @Pattern(regexp = "^AR-\\d{3}$") String ruleId,
            @RequestParam(required = false) @Pattern(regexp = "RULE|ML") String source,
            @RequestParam(required = false) @Pattern(regexp = "OPEN|UNDER_INVESTIGATION|CLOSED") String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "detectedAt") @Pattern(regexp = "detectedAt|transactionDate|amount|severity")
            String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        var filter = new ExceptionFilter(q, severity, riskCategory, ruleId, source, status, accountId, from, to);
        return service.search(filter, page, size, sort, "asc".equals(direction));
    }

    @GetMapping("/summary")
    @Operation(summary = "Exception counts by status, severity, risk category, rule and detection source")
    public ExceptionSummary summary() {
        return service.summary();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Exception detail: rule, evidence, flagged and related transactions, investigations (audited)")
    public ExceptionDetail detail(@PathVariable long id) {
        return service.detail(id);
    }
}
