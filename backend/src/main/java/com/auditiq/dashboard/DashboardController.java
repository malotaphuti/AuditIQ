package com.auditiq.dashboard;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.dashboard.DashboardDtos.Dashboard;
import com.auditiq.security.CurrentUser;
import com.auditiq.user.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/analytics")
@Tag(name = "Risk analytics", description = "Dashboard and dataset exports built on the analytics schema")
public class DashboardController {

    private final DashboardService dashboard;
    private final AnalyticsExportService exports;

    public DashboardController(DashboardService dashboard, AnalyticsExportService exports) {
        this.dashboard = dashboard;
        this.exports = exports;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Executive, transaction, risk and investigation analytics for a transaction-date range. "
            + "Account-level detail is included for auditors and audit managers only.")
    public Dashboard dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var user = CurrentUser.require();
        boolean accounts = user.hasRole(RoleName.AUDITOR) || user.hasRole(RoleName.AUDIT_MANAGER);
        return dashboard.build(from, to, accounts);
    }

    @GetMapping("/datasets")
    @PreAuthorize("hasAnyRole('AUDITOR','AUDIT_MANAGER')")
    @Operation(summary = "Names of the analytics datasets available for CSV export")
    public List<String> datasets() {
        return List.copyOf(AnalyticsExportService.DATASETS.keySet());
    }

    @GetMapping("/export/{dataset}")
    @PreAuthorize("hasAnyRole('AUDITOR','AUDIT_MANAGER')")
    @Operation(summary = "Download an analytics view as CSV (audited)")
    public void export(@PathVariable @Pattern(regexp = "^[a-z_]{1,40}$") String dataset, HttpServletResponse response)
            throws IOException {
        if (!AnalyticsExportService.exists(dataset)) {
            throw new NotFoundException("Unknown dataset " + dataset);
        }
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"auditiq_" + dataset + "_" + LocalDate.now() + ".csv\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        exports.export(dataset, writer);
    }
}
