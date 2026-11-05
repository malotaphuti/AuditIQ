package com.auditiq.exceptions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.common.PageResponse;
import com.auditiq.exceptions.ExceptionDtos.Count;
import com.auditiq.exceptions.ExceptionDtos.ExceptionDetail;
import com.auditiq.exceptions.ExceptionDtos.ExceptionFilter;
import com.auditiq.exceptions.ExceptionDtos.ExceptionRow;
import com.auditiq.exceptions.ExceptionDtos.ExceptionSummary;
import com.auditiq.exceptions.ExceptionDtos.ExecutionInfo;
import com.auditiq.exceptions.ExceptionDtos.InvestigationLink;
import com.auditiq.exceptions.ExceptionDtos.RelatedTransaction;
import com.auditiq.exceptions.ExceptionDtos.RuleInfo;
import com.auditiq.transaction.TransactionDtos.TransactionRow;
import com.auditiq.transaction.TransactionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to potential audit exceptions. Exceptions are findings for human review – they are never conclusions –
 * and their status changes only through the investigation workflow.
 */
@Service
public class AuditExceptionService {

    static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    static final Map<String, String> SORTS = Map.of(
            "detectedAt", "e.detected_at",
            "transactionDate", "t.transaction_date",
            "amount", "t.transaction_amount",
            "severity", "array_position(ARRAY['LOW','MEDIUM','HIGH','CRITICAL']::varchar[], e.severity)");

    private static final int ACTIVITY_DAYS = 7;
    private static final int RELATED_LIMIT = 50;

    public static final String FROM = """
             FROM audit_exceptions e
             JOIN transactions t ON t.transaction_id = e.transaction_id
             LEFT JOIN audit_rules r ON r.rule_id = e.rule_id
            """;

    public static final String ROW_SELECT = """
            SELECT e.id, e.exception_ref, e.detection_source, e.rule_id, r.rule_name, e.transaction_id, e.account_id,
                   t.transaction_date, t.transaction_amount, t.transaction_type, e.severity, e.risk_category, e.status,
                   e.reason, e.detected_at, i.id AS inv_id, i.investigation_ref, i.status AS inv_status,
                   u.full_name AS inv_assignee
            """ + FROM + """
             LEFT JOIN LATERAL (SELECT id, investigation_ref, status, assigned_to FROM investigations
                                WHERE exception_id = e.id ORDER BY created_at DESC, id DESC LIMIT 1) i ON true
             LEFT JOIN users u ON u.id = i.assigned_to
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public AuditExceptionService(NamedParameterJdbcTemplate jdbc, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<ExceptionRow> search(ExceptionFilter filter, int page, int size, String sort,
                                             boolean ascending) {
        var params = new MapSqlParameterSource();
        String where = where(filter, params);
        long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, params, Long.class);
        String orderBy = SORTS.getOrDefault(sort, "e.detected_at") + (ascending ? " ASC" : " DESC");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<ExceptionRow> rows = jdbc.query(ROW_SELECT + where + " ORDER BY " + orderBy
                + ", e.id DESC LIMIT :limit OFFSET :offset", params, ROW_MAPPER);
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public ExceptionSummary summary() {
        var none = new MapSqlParameterSource();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        SEVERITIES.forEach(s -> bySeverity.put(s, 0L));
        Map<String, Long> byStatus = new HashMap<>();
        Map<String, Long> bySource = new LinkedHashMap<>(Map.of("RULE", 0L, "ML", 0L));
        jdbc.query("SELECT severity, status, detection_source, count(*) FROM audit_exceptions GROUP BY 1, 2, 3", none,
                rs -> {
                    long c = rs.getLong(4);
                    bySeverity.merge(rs.getString(1), c, Long::sum);
                    byStatus.merge(rs.getString(2), c, Long::sum);
                    bySource.merge(rs.getString(3), c, Long::sum);
                });
        List<Count> byCategory = jdbc.query("""
                SELECT risk_category, count(*) FROM audit_exceptions GROUP BY 1 ORDER BY 2 DESC, 1
                """, none, (rs, i) -> new Count(rs.getString(1), rs.getString(1), rs.getLong(2)));
        List<Count> byRule = jdbc.query("""
                SELECT r.rule_id, r.rule_name, count(e.id) FROM audit_rules r
                LEFT JOIN audit_exceptions e ON e.rule_id = r.rule_id GROUP BY 1, 2 ORDER BY 1
                """, none, (rs, i) -> new Count(rs.getString(1), rs.getString(2), rs.getLong(3)));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new ExceptionSummary(total, byStatus.getOrDefault("OPEN", 0L),
                byStatus.getOrDefault("UNDER_INVESTIGATION", 0L), byStatus.getOrDefault("CLOSED", 0L), bySeverity,
                byCategory, byRule, bySource);
    }

    @Transactional(readOnly = true)
    public ExceptionDetail detail(long id) {
        var params = new MapSqlParameterSource("id", id);
        ExceptionRow row = jdbc.query(ROW_SELECT + " WHERE e.id = :id", params, ROW_MAPPER).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Exception " + id + " not found"));

        Map<String, Object> extra = jdbc.queryForMap("""
                SELECT e.details::text AS details, e.rule_execution_id, x.batch_ref, x.executed_by, x.started_at,
                       r.description, r.detection_logic, r.version
                FROM audit_exceptions e
                LEFT JOIN rule_executions x ON x.id = e.rule_execution_id
                LEFT JOIN audit_rules r ON r.rule_id = e.rule_id
                WHERE e.id = :id""", params);
        JsonNode details = read((String) extra.get("details"));
        RuleInfo rule = row.ruleId() == null ? null : new RuleInfo(row.ruleId(), row.ruleName(),
                (String) extra.get("description"), (String) extra.get("detection_logic"), row.riskCategory(),
                ((Number) extra.get("version")).intValue());
        ExecutionInfo execution = extra.get("rule_execution_id") == null ? null : new ExecutionInfo(
                ((Number) extra.get("rule_execution_id")).longValue(), (String) extra.get("batch_ref"),
                (String) extra.get("executed_by"), ((Timestamp) extra.get("started_at")).toInstant());

        TransactionRow transaction = jdbc.query(TransactionService.ROW_SELECT + " WHERE t.transaction_id = :tx",
                new MapSqlParameterSource("tx", row.transactionId()), TransactionService.ROW_MAPPER).getFirst();

        Set<String> linkedIds = new LinkedHashSet<>();
        details.path("relatedTransactionIds").forEach(n -> linkedIds.add(n.asText()));
        if (details.hasNonNull("matchedTransactionId")) {
            linkedIds.add(details.get("matchedTransactionId").asText());
        }
        linkedIds.remove(row.transactionId());
        List<RelatedTransaction> linked = related(linkedIds.isEmpty() ? List.of() : jdbc.query(
                TransactionService.ROW_SELECT + " WHERE t.transaction_id IN (:ids) ORDER BY t.transaction_date"
                        + " LIMIT " + RELATED_LIMIT,
                new MapSqlParameterSource("ids", linkedIds), TransactionService.ROW_MAPPER), "LINKED_BY_RULE");

        Set<String> exclude = new LinkedHashSet<>(linkedIds);
        exclude.add(row.transactionId());
        List<RelatedTransaction> activity = related(jdbc.query(TransactionService.ROW_SELECT + """
                 WHERE t.account_id = :account
                   AND t.transaction_date BETWEEN CAST(:at AS timestamptz) - make_interval(days => :days)
                                              AND CAST(:at AS timestamptz) + make_interval(days => :days)
                   AND t.transaction_id NOT IN (:exclude)
                 ORDER BY t.transaction_date LIMIT
                """ + RELATED_LIMIT, new MapSqlParameterSource("account", row.accountId())
                .addValue("at", Timestamp.from(row.transactionDate())).addValue("days", ACTIVITY_DAYS)
                .addValue("exclude", exclude), TransactionService.ROW_MAPPER), "SAME_ACCOUNT_WITHIN_7_DAYS");

        List<ExceptionRow> others = jdbc.query(ROW_SELECT + " WHERE e.transaction_id = :tx AND e.id <> :id"
                + " ORDER BY e.detected_at DESC", new MapSqlParameterSource("tx", row.transactionId())
                .addValue("id", id), ROW_MAPPER);
        List<InvestigationLink> investigations = jdbc.query("""
                SELECT i.id, i.investigation_ref, i.status, u.full_name FROM investigations i
                LEFT JOIN users u ON u.id = i.assigned_to
                WHERE i.exception_id = :id ORDER BY i.created_at DESC
                """, params, (rs, i) -> new InvestigationLink(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4)));

        audit.record(AuditAction.EXCEPTION_VIEWED, "AUDIT_EXCEPTION", id, Map.of("exceptionRef", row.exceptionRef()));
        return new ExceptionDetail(row, details, rule, execution, transaction, linked, activity, others,
                investigations);
    }

    private List<RelatedTransaction> related(List<TransactionRow> rows, String relation) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> refs = new HashMap<>();
        jdbc.query("SELECT transaction_id, exception_ref FROM audit_exceptions WHERE transaction_id IN (:ids)"
                        + " ORDER BY exception_ref",
                new MapSqlParameterSource("ids", rows.stream().map(TransactionRow::transactionId).toList()),
                rs -> { refs.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)); });
        return rows.stream().map(t -> new RelatedTransaction(t, relation,
                refs.getOrDefault(t.transactionId(), List.of()))).toList();
    }

    private static String where(ExceptionFilter f, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        eq(clauses, params, "e.severity", "severity", f.severity());
        eq(clauses, params, "e.risk_category", "riskCategory", f.riskCategory());
        eq(clauses, params, "e.rule_id", "ruleId", f.ruleId());
        eq(clauses, params, "e.detection_source", "source", f.source());
        eq(clauses, params, "e.status", "status", f.status());
        eq(clauses, params, "e.account_id", "accountId", f.accountId());
        if (f.q() != null && !f.q().isBlank()) {
            clauses.add("(e.exception_ref LIKE :q OR e.transaction_id LIKE :q)");
            params.addValue("q", f.q().trim().toUpperCase().replace("%", "").replace("_", "\\_") + "%");
        }
        if (f.from() != null) {
            clauses.add("t.transaction_date >= :fromTs");
            params.addValue("fromTs", Timestamp.from(f.from().atStartOfDay(TransactionService.BANK_ZONE).toInstant()));
        }
        if (f.to() != null) {
            clauses.add("t.transaction_date < :toTs");
            params.addValue("toTs", Timestamp.from(f.to().plusDays(1).atStartOfDay(TransactionService.BANK_ZONE)
                    .toInstant()));
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static void eq(List<String> clauses, MapSqlParameterSource params, String column, String name,
                           String value) {
        if (value != null && !value.isBlank()) {
            clauses.add(column + " = :" + name);
            params.addValue(name, value.trim().toUpperCase());
        }
    }

    private JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored exception details are invalid", ex);
        }
    }

    public static final RowMapper<ExceptionRow> ROW_MAPPER = (rs, i) -> new ExceptionRow(rs.getLong("id"),
            rs.getString("exception_ref"), rs.getString("detection_source"), rs.getString("rule_id"),
            rs.getString("rule_name"), rs.getString("transaction_id"), rs.getString("account_id"),
            instant(rs, "transaction_date"), rs.getBigDecimal("transaction_amount"), rs.getString("transaction_type"),
            rs.getString("severity"), rs.getString("risk_category"), rs.getString("status"), rs.getString("reason"),
            instant(rs, "detected_at"), rs.getObject("inv_id") == null ? null : new InvestigationLink(
                    rs.getLong("inv_id"), rs.getString("investigation_ref"), rs.getString("inv_status"),
                    rs.getString("inv_assignee")));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
