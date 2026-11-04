package com.auditiq.overview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.auditiq.audit.AuditLogService;
import com.auditiq.overview.OverviewResponse.CategoryCount;
import com.auditiq.overview.OverviewResponse.ExceptionKpis;
import com.auditiq.overview.OverviewResponse.LastRun;
import com.auditiq.overview.OverviewResponse.PipelineHealth;
import com.auditiq.overview.OverviewResponse.RecentException;
import com.auditiq.overview.OverviewResponse.TransactionKpis;
import com.auditiq.overview.OverviewResponse.TrendPoint;
import com.auditiq.security.AuthenticatedUser;
import com.auditiq.user.RoleName;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregates the overview dashboard directly from the curated tables. */
@Service
public class OverviewService {

    static final List<String> INVESTIGATION_STATUSES =
            List.of("NEW", "UNDER_REVIEW", "AWAITING_EVIDENCE", "ESCALATED", "RESOLVED");
    static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OverviewService(JdbcTemplate jdbc, AuditLogService auditLogService, Clock clock) {
        this.jdbc = jdbc;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OverviewResponse build(AuthenticatedUser viewer) {
        TransactionKpis tx = jdbc.queryForObject("""
                SELECT count(*) AS total, coalesce(sum(transaction_amount), 0) AS total_value,
                       min(transaction_date) AS first_at, max(transaction_date) AS last_at
                FROM transactions
                """, (rs, i) -> new TransactionKpis(rs.getLong("total"), rs.getBigDecimal("total_value"),
                instant(rs, "first_at"), instant(rs, "last_at")));

        Map<String, Long> bySeverity = zeroFilled(SEVERITIES);
        jdbc.query("SELECT severity, count(*) FROM audit_exceptions GROUP BY severity",
                rs -> { bySeverity.put(rs.getString(1), rs.getLong(2)); });
        Map<String, Long> bySource = zeroFilled(List.of("RULE", "ML"));
        jdbc.query("SELECT detection_source, count(*) FROM audit_exceptions GROUP BY detection_source",
                rs -> { bySource.put(rs.getString(1), rs.getLong(2)); });
        long totalExceptions = bySource.values().stream().mapToLong(Long::longValue).sum();
        long open = count("SELECT count(*) FROM audit_exceptions WHERE status <> 'CLOSED'");
        long flaggedTransactions = count("SELECT count(DISTINCT transaction_id) FROM audit_exceptions");
        BigDecimal rate = tx.total() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(flaggedTransactions * 100.0 / tx.total()).setScale(2, RoundingMode.HALF_UP);
        ExceptionKpis exceptions = new ExceptionKpis(totalExceptions,
                bySeverity.get("HIGH") + bySeverity.get("CRITICAL"), open, rate, bySeverity, bySource);

        Map<String, Long> investigations = zeroFilled(INVESTIGATION_STATUSES);
        jdbc.query("SELECT status, count(*) FROM investigations GROUP BY status",
                rs -> { investigations.put(rs.getString(1), rs.getLong(2)); });

        return new OverviewResponse(clock.instant(), tx, exceptions, investigations, pipelineHealth(),
                trend(), riskDistribution(), recentExceptions(),
                auditLogService.recent(viewer, viewer.hasRole(RoleName.ADMIN) || viewer.hasRole(RoleName.AUDIT_MANAGER)));
    }

    private PipelineHealth pipelineHealth() {
        long total = count("SELECT count(*) FROM pipeline_runs");
        long ok = count("SELECT count(*) FROM pipeline_runs WHERE status = 'SUCCESS'");
        List<LastRun> last = jdbc.query("""
                SELECT r.run_ref, r.status, r.started_at, r.duration_ms,
                       q.total_records, q.valid_records, q.rejected_records
                FROM pipeline_runs r LEFT JOIN data_quality_reports q ON q.pipeline_run_id = r.id
                ORDER BY r.started_at DESC LIMIT 1
                """, (rs, i) -> new LastRun(rs.getString("run_ref"), rs.getString("status"),
                instant(rs, "started_at"), (Long) rs.getObject("duration_ms", Long.class),
                rs.getObject("total_records", Integer.class), rs.getObject("valid_records", Integer.class),
                rs.getObject("rejected_records", Integer.class)));
        return new PipelineHealth(total, ok, last.isEmpty() ? null : last.getFirst());
    }

    /** Daily volume over the most recent 90 days of available data (anchored on the latest transaction). */
    private List<TrendPoint> trend() {
        return jdbc.query("""
                WITH bounds AS (SELECT max(transaction_date) AS max_d FROM transactions)
                SELECT date_trunc('day', t.transaction_date)::date AS d, count(*) AS c,
                       sum(t.transaction_amount) AS v
                FROM transactions t, bounds b
                WHERE t.transaction_date > b.max_d - interval '90 days'
                GROUP BY 1 ORDER BY 1
                """, (rs, i) -> new TrendPoint(rs.getDate("d").toLocalDate(), rs.getLong("c"), rs.getBigDecimal("v")));
    }

    private List<CategoryCount> riskDistribution() {
        return jdbc.query("""
                SELECT risk_category, count(*) FROM audit_exceptions GROUP BY risk_category ORDER BY 2 DESC
                """, (rs, i) -> new CategoryCount(rs.getString(1), rs.getLong(2)));
    }

    private List<RecentException> recentExceptions() {
        return jdbc.query("""
                SELECT id, exception_ref, detection_source, rule_id, transaction_id, account_id, severity,
                       risk_category, status, detected_at
                FROM audit_exceptions ORDER BY detected_at DESC, id DESC LIMIT 8
                """, (rs, i) -> new RecentException(rs.getLong("id"), rs.getString("exception_ref"),
                rs.getString("detection_source"), rs.getString("rule_id"), rs.getString("transaction_id"),
                rs.getString("account_id"), rs.getString("severity"), rs.getString("risk_category"),
                rs.getString("status"), instant(rs, "detected_at")));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static Map<String, Long> zeroFilled(List<String> keys) {
        Map<String, Long> map = new LinkedHashMap<>();
        keys.forEach(k -> map.put(k, 0L));
        return map;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
