package com.auditiq.investigation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLogRepository;
import com.auditiq.audit.AuditLogResponse;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.BusinessRuleException;
import com.auditiq.common.Exceptions.ConflictException;
import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.common.PageResponse;
import com.auditiq.exceptions.ExceptionDtos.ExceptionRow;
import com.auditiq.investigation.InvestigationDtos.AssignmentRequest;
import com.auditiq.investigation.InvestigationDtos.CreateInvestigationRequest;
import com.auditiq.investigation.InvestigationDtos.Evidence;
import com.auditiq.investigation.InvestigationDtos.InvestigationDetail;
import com.auditiq.investigation.InvestigationDtos.InvestigationRow;
import com.auditiq.investigation.InvestigationDtos.InvestigationSummary;
import com.auditiq.investigation.InvestigationDtos.Note;
import com.auditiq.investigation.InvestigationDtos.NoteRequest;
import com.auditiq.investigation.InvestigationDtos.StatusChange;
import com.auditiq.investigation.InvestigationDtos.StatusChangeRequest;
import com.auditiq.investigation.InvestigationDtos.UserRef;
import com.auditiq.investigation.InvestigationWorkflow.Outcome;
import com.auditiq.investigation.InvestigationWorkflow.Status;
import com.auditiq.security.AuthenticatedUser;
import com.auditiq.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Investigation case management: opening a case from a potential exception, assignment, notes, status changes and
 * the final human-reviewed outcome. Every change is optimistic-locked, recorded in the status history where relevant
 * and written to the audit trail.
 */
@Service
public class InvestigationService {

    static final Map<String, String> SORTS = Map.of(
            "updatedAt", "i.updated_at",
            "createdAt", "i.created_at",
            "priority", "array_position(ARRAY['LOW','MEDIUM','HIGH','CRITICAL']::varchar[], i.priority)");

    private static final String FROM = """
             FROM investigations i
             JOIN audit_exceptions e ON e.id = i.exception_id
             JOIN transactions t ON t.transaction_id = e.transaction_id
             LEFT JOIN audit_rules r ON r.rule_id = e.rule_id
             LEFT JOIN users a ON a.id = i.assigned_to
             JOIN users c ON c.id = i.created_by
            """;

    private static final String ROW_SELECT = """
            SELECT i.id, i.investigation_ref, i.title, i.status, i.priority, i.exception_id, e.exception_ref,
                   e.rule_id, r.rule_name, e.transaction_id, e.account_id, t.transaction_amount, i.outcome,
                   i.created_at, i.updated_at, i.resolved_at,
                   a.id AS a_id, a.username AS a_username, a.full_name AS a_name,
                   c.id AS c_id, c.username AS c_username, c.full_name AS c_name
            """ + FROM;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditLogService audit;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper mapper;

    public InvestigationService(NamedParameterJdbcTemplate jdbc, AuditLogService audit, AuditLogRepository auditLogs,
                                ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------------------------
    @Transactional(readOnly = true)
    public PageResponse<InvestigationRow> search(String status, String priority, String assignee, String q, int page,
                                                 int size, String sort, boolean ascending) {
        var params = new MapSqlParameterSource();
        List<String> clauses = new ArrayList<>();
        if (status != null) {
            if ("OPEN".equals(status)) {
                clauses.add("i.status <> 'RESOLVED'");
            } else {
                clauses.add("i.status = :status");
                params.addValue("status", status);
            }
        }
        if (priority != null) {
            clauses.add("i.priority = :priority");
            params.addValue("priority", priority);
        }
        if ("me".equals(assignee)) {
            clauses.add("i.assigned_to = :me");
            params.addValue("me", CurrentUser.require().id());
        } else if ("unassigned".equals(assignee)) {
            clauses.add("i.assigned_to IS NULL");
        } else if (assignee != null) {
            clauses.add("i.assigned_to = :assignee");
            params.addValue("assignee", Long.parseLong(assignee));
        }
        if (q != null && !q.isBlank()) {
            clauses.add("(i.investigation_ref LIKE :q OR e.exception_ref LIKE :q OR e.transaction_id LIKE :q"
                    + " OR e.account_id LIKE :q)");
            params.addValue("q", q.trim().toUpperCase().replace("%", "").replace("_", "\\_") + "%");
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, params, Long.class);
        String orderBy = SORTS.getOrDefault(sort, "i.updated_at") + (ascending ? " ASC" : " DESC");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<InvestigationRow> rows = jdbc.query(ROW_SELECT + where + " ORDER BY " + orderBy
                + ", i.id DESC LIMIT :limit OFFSET :offset", params, ROW_MAPPER);
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public InvestigationSummary summary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Arrays.stream(Status.values()).forEach(s -> byStatus.put(s.name(), 0L));
        var none = new MapSqlParameterSource();
        jdbc.query("SELECT status, count(*) FROM investigations GROUP BY status", none,
                rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        long open = byStatus.entrySet().stream().filter(e -> !e.getKey().equals("RESOLVED"))
                .mapToLong(Map.Entry::getValue).sum();
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status <> 'RESOLVED' AND assigned_to = :me) AS mine,
                       count(*) FILTER (WHERE status <> 'RESOLVED' AND assigned_to IS NULL) AS unassigned,
                       avg(extract(epoch FROM resolved_at - created_at) / 3600)
                           FILTER (WHERE status = 'RESOLVED') AS avg_hours,
                       (SELECT count(*) FROM audit_exceptions WHERE status = 'OPEN') AS awaiting
                FROM investigations
                """, new MapSqlParameterSource("me", CurrentUser.require().id()));
        Number avg = (Number) counts.get("avg_hours");
        return new InvestigationSummary(byStatus, open, ((Number) counts.get("mine")).longValue(),
                ((Number) counts.get("unassigned")).longValue(), ((Number) counts.get("awaiting")).longValue(),
                avg == null ? null : Math.round(avg.doubleValue() * 10) / 10.0);
    }

    @Transactional(readOnly = true)
    public InvestigationDetail detail(long id) {
        var params = new MapSqlParameterSource("id", id);
        InvestigationRow row = jdbc.query(ROW_SELECT + " WHERE i.id = :id", params, ROW_MAPPER).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Investigation " + id + " not found"));
        Map<String, Object> extra = jdbc.queryForMap("""
                SELECT i.version, i.outcome_summary, i.reviewed_at, u.id AS u_id, u.username AS u_username,
                       u.full_name AS u_name
                FROM investigations i LEFT JOIN users u ON u.id = i.reviewed_by WHERE i.id = :id""", params);
        ExceptionRow exception = jdbc.query(com.auditiq.exceptions.AuditExceptionService.ROW_SELECT
                + " WHERE e.id = :eid", new MapSqlParameterSource("eid", row.exceptionId()),
                com.auditiq.exceptions.AuditExceptionService.ROW_MAPPER).getFirst();

        List<Note> notes = jdbc.query("""
                SELECT n.id, n.note, n.created_at, u.id AS u_id, u.username AS u_username, u.full_name AS u_name
                FROM investigation_notes n JOIN users u ON u.id = n.author_id
                WHERE n.investigation_id = :id ORDER BY n.created_at DESC, n.id DESC
                """, params, (rs, i) -> new Note(rs.getLong("id"), user(rs, "u"), rs.getString("note"),
                instant(rs, "created_at")));
        List<StatusChange> history = jdbc.query("""
                SELECT h.id, h.from_status, h.to_status, h.reason, h.changed_at,
                       u.id AS u_id, u.username AS u_username, u.full_name AS u_name
                FROM investigation_status_history h JOIN users u ON u.id = h.changed_by
                WHERE h.investigation_id = :id ORDER BY h.changed_at DESC, h.id DESC
                """, params, (rs, i) -> new StatusChange(rs.getLong("id"), rs.getString("from_status"),
                rs.getString("to_status"), user(rs, "u"), rs.getString("reason"), instant(rs, "changed_at")));
        List<Evidence> evidence = jdbc.query("""
                SELECT v.id, v.evidence_type, v.reference_id, v.description, v.content::text AS content,
                       v.ai_generated, v.human_review_status, v.created_at,
                       u.id AS u_id, u.username AS u_username, u.full_name AS u_name
                FROM investigation_evidence v LEFT JOIN users u ON u.id = v.added_by
                WHERE v.investigation_id = :id ORDER BY v.created_at, v.id
                """, params, (rs, i) -> new Evidence(rs.getLong("id"), rs.getString("evidence_type"),
                rs.getString("reference_id"), rs.getString("description"), read(rs.getString("content")),
                rs.getBoolean("ai_generated"), rs.getString("human_review_status"), user(rs, "u"),
                instant(rs, "created_at")));
        List<AuditLogResponse> activity = auditLogs
                .findTop100ByEntityTypeAndEntityIdOrderByOccurredAtDesc("INVESTIGATION", String.valueOf(id))
                .stream().map(AuditLogResponse::from).toList();

        Status status = Status.valueOf(row.status());
        UserRef reviewer = extra.get("u_id") == null ? null : new UserRef(((Number) extra.get("u_id")).longValue(),
                (String) extra.get("u_username"), (String) extra.get("u_name"));
        Timestamp reviewedAt = (Timestamp) extra.get("reviewed_at");
        return new InvestigationDetail(row, ((Number) extra.get("version")).intValue(),
                (String) extra.get("outcome_summary"), reviewer, reviewedAt == null ? null : reviewedAt.toInstant(),
                exception, notes, history, evidence, activity, InvestigationWorkflow.allowedTransitions(status),
                List.of(Outcome.values()));
    }

    @Transactional(readOnly = true)
    public List<UserRef> assignees() {
        return jdbc.query("""
                SELECT DISTINCT u.id, u.username, u.full_name FROM users u
                JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
                WHERE u.enabled AND r.name IN ('AUDITOR', 'AUDIT_MANAGER') ORDER BY u.full_name
                """, new MapSqlParameterSource(), (rs, i) -> new UserRef(rs.getLong(1), rs.getString(2),
                rs.getString(3)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------------------------
    @Transactional
    public InvestigationDetail create(CreateInvestigationRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        var exParams = new MapSqlParameterSource("eid", request.exceptionId());
        // Lock the exception so two auditors cannot open parallel cases for it.
        Map<String, Object> ex = jdbc.query("""
                SELECT e.id, e.exception_ref, e.severity, e.transaction_id, e.account_id, e.rule_id, e.reason,
                       e.details::text AS details, e.detection_source, r.rule_name
                FROM audit_exceptions e LEFT JOIN audit_rules r ON r.rule_id = e.rule_id
                WHERE e.id = :eid FOR UPDATE OF e
                """, exParams, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String c : List.of("exception_ref", "severity", "transaction_id", "account_id", "rule_id",
                            "reason", "details", "detection_source", "rule_name")) {
                        m.put(c, rs.getString(c));
                    }
                    return m;
                }).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Exception " + request.exceptionId() + " not found"));

        List<String> existing = jdbc.queryForList(
                "SELECT investigation_ref FROM investigations WHERE exception_id = :eid", exParams, String.class);
        if (!existing.isEmpty()) {
            throw new ConflictException("Exception " + ex.get("exception_ref") + " already has investigation "
                    + existing.getFirst() + ". Re-open it instead of starting a new one.");
        }
        if (request.assigneeId() != null) {
            requireAssignable(request.assigneeId());
        }

        String title = request.title() != null && !request.title().isBlank() ? request.title().strip()
                : (ex.get("rule_name") != null ? ex.get("rule_name") : "Model finding") + " – "
                + ex.get("transaction_id");
        String priority = request.priority() != null ? request.priority() : (String) ex.get("severity");
        var insert = new MapSqlParameterSource("eid", request.exceptionId()).addValue("title", title)
                .addValue("priority", priority).addValue("assignee", request.assigneeId())
                .addValue("actor", actor.id());
        Map<String, Object> created = jdbc.queryForMap("""
                INSERT INTO investigations (investigation_ref, exception_id, title, priority, assigned_to, created_by)
                VALUES ('INV-' || lpad(nextval('investigation_ref_seq')::text, 6, '0'), :eid, :title, :priority,
                        :assignee, :actor)
                RETURNING id, investigation_ref""", insert);
        long id = ((Number) created.get("id")).longValue();
        String ref = (String) created.get("investigation_ref");

        history(id, null, Status.NEW, actor.id(), "Investigation opened from " + ex.get("exception_ref"));
        jdbc.update("UPDATE audit_exceptions SET status = 'UNDER_INVESTIGATION' WHERE id = :eid", exParams);
        addSystemEvidence(id, ex, actor.id());
        if (request.note() != null && !request.note().isBlank()) {
            insertNote(id, actor.id(), request.note().strip());
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("investigationRef", ref);
        details.put("exceptionRef", ex.get("exception_ref"));
        details.put("priority", priority);
        if (request.assigneeId() != null) details.put("assigneeId", request.assigneeId());
        audit.record(AuditAction.INVESTIGATION_CREATED, "INVESTIGATION", id, details);
        return detail(id);
    }

    @Transactional
    public InvestigationDetail changeStatus(long id, StatusChangeRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        Locked current = lock(id, request.version());
        Status from = current.status();
        Status to = request.status();
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        InvestigationWorkflow.validate(from, to, reason, request.outcome(), request.outcomeSummary(),
                current.assignedTo() != null);

        var params = new MapSqlParameterSource("id", id).addValue("status", to.name()).addValue("actor", actor.id());
        if (to == Status.RESOLVED) {
            params.addValue("outcome", request.outcome().name())
                    .addValue("summary", request.outcomeSummary().strip());
            jdbc.update("""
                    UPDATE investigations SET status = :status, outcome = :outcome, outcome_summary = :summary,
                           reviewed_by = :actor, reviewed_at = now(), resolved_at = now(), version = version + 1,
                           updated_at = now()
                    WHERE id = :id""", params);
        } else if (from == Status.RESOLVED) {
            jdbc.update("""
                    UPDATE investigations SET status = :status, outcome = NULL, outcome_summary = NULL,
                           reviewed_by = NULL, reviewed_at = NULL, resolved_at = NULL, version = version + 1,
                           updated_at = now()
                    WHERE id = :id""", params);
        } else {
            jdbc.update("UPDATE investigations SET status = :status, version = version + 1, updated_at = now()"
                    + " WHERE id = :id", params);
        }

        String historyReason = reason;
        if (to == Status.RESOLVED) {
            historyReason = "Outcome recorded: " + request.outcome().name() + (reason == null ? "" : ". " + reason);
        } else if (from == Status.RESOLVED) {
            historyReason = "Re-opened (previous outcome: " + current.outcome() + "). " + reason;
        }
        history(id, from, to, actor.id(), historyReason);
        jdbc.update("UPDATE audit_exceptions SET status = :s WHERE id = :eid", new MapSqlParameterSource(
                "s", InvestigationWorkflow.exceptionStatusFor(to)).addValue("eid", current.exceptionId()));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("investigationRef", current.ref());
        details.put("from", from.name());
        details.put("to", to.name());
        if (reason != null) details.put("reason", reason);
        AuditAction action = AuditAction.INVESTIGATION_STATUS_CHANGED;
        if (to == Status.RESOLVED) {
            action = AuditAction.INVESTIGATION_OUTCOME_RECORDED;
            details.put("outcome", request.outcome().name());
            details.put("outcomeSummary", request.outcomeSummary().strip());
        } else if (from == Status.RESOLVED) {
            action = AuditAction.INVESTIGATION_REOPENED;
            details.put("previousOutcome", current.outcome());
        }
        audit.record(action, "INVESTIGATION", id, details);
        return detail(id);
    }

    @Transactional
    public InvestigationDetail assign(long id, AssignmentRequest request) {
        Locked current = lock(id, request.version());
        if (current.status() == Status.RESOLVED) {
            throw new BusinessRuleException("A resolved investigation cannot be re-assigned; re-open it first");
        }
        if (java.util.Objects.equals(current.assignedTo(), request.assigneeId())) {
            throw new BusinessRuleException("The investigation is already assigned to that user");
        }
        UserRef assignee = request.assigneeId() == null ? null : requireAssignable(request.assigneeId());
        jdbc.update("""
                UPDATE investigations SET assigned_to = :assignee, version = version + 1, updated_at = now()
                WHERE id = :id""", new MapSqlParameterSource("id", id).addValue("assignee", request.assigneeId()));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("investigationRef", current.ref());
        details.put("fromUserId", current.assignedTo());
        details.put("toUserId", request.assigneeId());
        details.put("toUsername", assignee == null ? null : assignee.username());
        audit.record(AuditAction.INVESTIGATION_ASSIGNED, "INVESTIGATION", id, details);
        return detail(id);
    }

    @Transactional
    public InvestigationDetail addNote(long id, NoteRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        Map<String, Object> row = jdbc.query("SELECT investigation_ref, status FROM investigations WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> Map.<String, Object>of("ref",
                        rs.getString(1), "status", rs.getString(2))).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Investigation " + id + " not found"));
        if (Status.RESOLVED.name().equals(row.get("status"))) {
            throw new BusinessRuleException("Notes cannot be added to a resolved investigation; re-open it first");
        }
        long noteId = insertNote(id, actor.id(), request.note().strip());
        jdbc.update("UPDATE investigations SET updated_at = now() WHERE id = :id", new MapSqlParameterSource("id", id));
        audit.record(AuditAction.INVESTIGATION_NOTE_ADDED, "INVESTIGATION", id, Map.of("investigationRef",
                row.get("ref"), "noteId", noteId, "length", request.note().strip().length()));
        return detail(id);
    }

    // ---------------------------------------------------------------------------------------------------------------
    private record Locked(String ref, Status status, Long assignedTo, long exceptionId, String outcome) {
    }

    private Locked lock(long id, int expectedVersion) {
        Locked locked = jdbc.query("""
                SELECT investigation_ref, status, assigned_to, exception_id, outcome, version FROM investigations
                WHERE id = :id FOR UPDATE
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
                    if (rs.getInt("version") != expectedVersion) {
                        throw new OptimisticLockingFailureException("Investigation " + id + " was modified");
                    }
                    return new Locked(rs.getString(1), Status.valueOf(rs.getString(2)),
                            rs.getObject(3, Long.class), rs.getLong(4), rs.getString(5));
                }).stream().findFirst().orElse(null);
        if (locked == null) {
            throw new NotFoundException("Investigation " + id + " not found");
        }
        return locked;
    }

    private UserRef requireAssignable(long userId) {
        return assignees().stream().filter(u -> u.id() == userId).findFirst().orElseThrow(() ->
                new BusinessRuleException("Investigations can only be assigned to active auditors or audit managers"));
    }

    private void history(long id, Status from, Status to, long actorId, String reason) {
        jdbc.update("""
                INSERT INTO investigation_status_history (investigation_id, from_status, to_status, changed_by, reason)
                VALUES (:id, :from, :to, :actor, :reason)""", new MapSqlParameterSource("id", id)
                .addValue("from", from == null ? null : from.name()).addValue("to", to.name())
                .addValue("actor", actorId).addValue("reason", reason));
    }

    private long insertNote(long id, long authorId, String note) {
        return jdbc.queryForObject("""
                INSERT INTO investigation_notes (investigation_id, author_id, note) VALUES (:id, :author, :note)
                RETURNING id""", new MapSqlParameterSource("id", id).addValue("author", authorId)
                .addValue("note", note), Long.class);
    }

    /** Snapshot of the flagged transaction and the detection result, captured when the case is opened. */
    private void addSystemEvidence(long id, Map<String, Object> ex, long actorId) {
        jdbc.update("""
                INSERT INTO investigation_evidence (investigation_id, evidence_type, reference_id, description, content,
                                                    added_by)
                SELECT :id, 'TRANSACTION', t.transaction_id,
                       'Flagged transaction as recorded in the curated layer when the investigation was opened',
                       jsonb_build_object('transactionId', t.transaction_id, 'accountId', t.account_id,
                                          'customerId', t.customer_id, 'transactionDate', t.transaction_date,
                                          'amount', t.transaction_amount, 'currency', t.currency,
                                          'type', t.transaction_type, 'channel', t.transaction_channel,
                                          'merchantId', t.merchant_id, 'status', t.transaction_status,
                                          'approvalThreshold', t.approval_threshold,
                                          'approvalStatus', t.approval_status, 'pipelineRunId', t.pipeline_run_id),
                       :actor
                FROM transactions t WHERE t.transaction_id = :tx""", new MapSqlParameterSource("id", id)
                .addValue("tx", ex.get("transaction_id")).addValue("actor", actorId));
        boolean rule = "RULE".equals(ex.get("detection_source"));
        jdbc.update("""
                INSERT INTO investigation_evidence (investigation_id, evidence_type, reference_id, description, content,
                                                    added_by)
                VALUES (:id, :type, :ref, :description,
                        jsonb_build_object('ruleId', CAST(:ruleId AS varchar), 'reason', CAST(:reason AS text),
                                           'details', CAST(:details AS jsonb)), :actor)""",
                new MapSqlParameterSource("id", id).addValue("type", rule ? "RULE_RESULT" : "MODEL_RESULT")
                        .addValue("ref", ex.get("exception_ref"))
                        .addValue("description", (rule ? "Audit rule " + ex.get("rule_id") + " (" + ex.get("rule_name")
                                + ")" : "Anomaly model") + " finding: " + ex.get("reason"))
                        .addValue("ruleId", ex.get("rule_id")).addValue("reason", ex.get("reason"))
                        .addValue("details", ex.get("details")).addValue("actor", actorId));
    }

    private JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UserRef user(ResultSet rs, String prefix) throws SQLException {
        Object id = rs.getObject(prefix + "_id");
        return id == null ? null : new UserRef(((Number) id).longValue(), rs.getString(prefix + "_username"),
                rs.getString(prefix + "_name"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static final RowMapper<InvestigationRow> ROW_MAPPER = (rs, i) -> new InvestigationRow(rs.getLong("id"),
            rs.getString("investigation_ref"), rs.getString("title"), rs.getString("status"),
            rs.getString("priority"), rs.getLong("exception_id"), rs.getString("exception_ref"),
            rs.getString("rule_id"), rs.getString("rule_name"), rs.getString("transaction_id"),
            rs.getString("account_id"), rs.getBigDecimal("transaction_amount"), user(rs, "a"), user(rs, "c"),
            rs.getString("outcome"), instant(rs, "created_at"), instant(rs, "updated_at"),
            instant(rs, "resolved_at"));
}
