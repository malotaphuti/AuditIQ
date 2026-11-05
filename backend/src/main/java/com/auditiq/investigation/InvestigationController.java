package com.auditiq.investigation;

import java.util.List;

import com.auditiq.common.PageResponse;
import com.auditiq.investigation.InvestigationDtos.AssignmentRequest;
import com.auditiq.investigation.InvestigationDtos.CreateInvestigationRequest;
import com.auditiq.investigation.InvestigationDtos.InvestigationDetail;
import com.auditiq.investigation.InvestigationDtos.InvestigationRow;
import com.auditiq.investigation.InvestigationDtos.InvestigationSummary;
import com.auditiq.investigation.InvestigationDtos.NoteRequest;
import com.auditiq.investigation.InvestigationDtos.StatusChangeRequest;
import com.auditiq.investigation.InvestigationDtos.UserRef;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/investigations")
@PreAuthorize("hasAnyRole('AUDITOR','AUDIT_MANAGER')")
@Tag(name = "Investigations", description = "Case management with human-reviewed outcomes")
public class InvestigationController {

    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search investigations (status OPEN = any status except RESOLVED; assignee = me | unassigned | id)")
    public PageResponse<InvestigationRow> search(
            @RequestParam(required = false)
            @Pattern(regexp = "OPEN|NEW|UNDER_REVIEW|AWAITING_EVIDENCE|ESCALATED|RESOLVED") String status,
            @RequestParam(required = false) @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String priority,
            @RequestParam(required = false) @Pattern(regexp = "me|unassigned|\\d{1,18}") String assignee,
            @RequestParam(required = false) @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_ -]{1,40}$") String q,
            @RequestParam(defaultValue = "updatedAt") @Pattern(regexp = "updatedAt|createdAt|priority") String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return service.search(status, priority, assignee, q, page, size, sort, "asc".equals(direction));
    }

    @GetMapping("/summary")
    @Operation(summary = "Investigation counts by status, workload and average resolution time")
    public InvestigationSummary summary() {
        return service.summary();
    }

    @GetMapping("/assignees")
    @Operation(summary = "Users an investigation can be assigned to (active auditors and audit managers)")
    public List<UserRef> assignees() {
        return service.assignees();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open an investigation for a potential exception")
    public InvestigationDetail create(@Valid @RequestBody CreateInvestigationRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Investigation detail with exception, evidence, notes, status history and audit activity")
    public InvestigationDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Change status; resolving records the human-reviewed outcome (optimistic locking via version)")
    public InvestigationDetail changeStatus(@PathVariable long id, @Valid @RequestBody StatusChangeRequest request) {
        return service.changeStatus(id, request);
    }

    @PutMapping("/{id}/assignment")
    @Operation(summary = "Assign (or unassign with assigneeId = null) the investigation")
    public InvestigationDetail assign(@PathVariable long id, @Valid @RequestBody AssignmentRequest request) {
        return service.assign(id, request);
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an investigation note")
    public InvestigationDetail addNote(@PathVariable long id, @Valid @RequestBody NoteRequest request) {
        return service.addNote(id, request);
    }
}
