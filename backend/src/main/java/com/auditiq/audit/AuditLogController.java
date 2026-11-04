package com.auditiq.audit;

import java.time.Instant;
import java.util.Map;

import com.auditiq.audit.AuditLogService.AuditLogQuery;
import com.auditiq.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/admin/audit-logs")
@Tag(name = "Audit trail")
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDIT_MANAGER')")
    @Operation(summary = "Search the audit trail (newest first)")
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        var result = service.search(new AuditLogQuery(action, username, entityType, entityId, from, to), page, size);
        service.record(AuditAction.AUDIT_LOG_VIEWED, "AUDIT_LOG", null,
                Map.of("page", page, "filters", filtersOf(action, username, entityType, entityId)));
        return result;
    }

    private static Map<String, String> filtersOf(String action, String username, String entityType, String entityId) {
        var map = new java.util.LinkedHashMap<String, String>();
        if (action != null) map.put("action", action);
        if (username != null) map.put("username", username);
        if (entityType != null) map.put("entityType", entityType);
        if (entityId != null) map.put("entityId", entityId);
        return map;
    }
}
