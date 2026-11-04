package com.auditiq.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.auditiq.common.PageResponse;
import com.auditiq.transaction.TransactionDtos.TransactionAnalytics;
import com.auditiq.transaction.TransactionDtos.TransactionDetail;
import com.auditiq.transaction.TransactionDtos.TransactionFilter;
import com.auditiq.transaction.TransactionDtos.TransactionRow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.DecimalMin;
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
@RequestMapping("/api/transactions")
@Tag(name = "Transactions")
public class TransactionController {

    private static final String CODE = "^[A-Za-z0-9_ -]{1,40}$";

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','AUDIT_MANAGER')")
    @Operation(summary = "Search curated transactions (paginated, filterable)")
    public PageResponse<TransactionRow> search(
            @RequestParam(required = false) @Size(max = 40) @Pattern(regexp = CODE) String q,
            @RequestParam(required = false) @Pattern(regexp = CODE) String accountId,
            @RequestParam(required = false) @Pattern(regexp = CODE) String customerId,
            @RequestParam(required = false) @Pattern(regexp = CODE) String merchantId,
            @RequestParam(required = false) @Pattern(regexp = CODE) String type,
            @RequestParam(required = false) @Pattern(regexp = CODE) String channel,
            @RequestParam(required = false) @Pattern(regexp = CODE) String category,
            @RequestParam(required = false) @Pattern(regexp = CODE) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String approvalStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minAmount,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxAmount,
            @RequestParam(defaultValue = "transactionDate") @Pattern(regexp = "transactionDate|amount|accountId")
            String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        var filter = new TransactionFilter(q, accountId, customerId, merchantId, type, channel, category, status,
                approvalStatus, from, to, minAmount, maxAmount);
        return service.search(filter, page, size, sort, "asc".equals(direction));
    }

    @GetMapping("/analytics")
    @Operation(summary = "Aggregated transaction analytics (volume, value, category, channel, type, location)")
    public TransactionAnalytics analytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.analytics(from, to);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AUDITOR','AUDIT_MANAGER')")
    @Operation(summary = "Transaction detail with account context, lineage and any linked exceptions")
    public TransactionDetail detail(@PathVariable @Pattern(regexp = "^[A-Za-z0-9-]{1,40}$") String id) {
        return service.detail(id.toUpperCase());
    }
}
