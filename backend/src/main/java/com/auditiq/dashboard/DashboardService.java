package com.auditiq.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.auditiq.dashboard.DashboardDtos.AccountRisk;
import com.auditiq.dashboard.DashboardDtos.Bucket;
import com.auditiq.dashboard.DashboardDtos.DailyPoint;
import com.auditiq.dashboard.DashboardDtos.Dashboard;
import com.auditiq.dashboard.DashboardDtos.DetectorCount;
import com.auditiq.dashboard.DashboardDtos.DetectorOverlap;
import com.auditiq.dashboard.DashboardDtos.Executive;
import com.auditiq.dashboard.DashboardDtos.InvestigationMonitoring;
import com.auditiq.dashboard.DashboardDtos.Pipeline;
import com.auditiq.dashboard.DashboardDtos.RiskAnalysis;
import com.auditiq.dashboard.DashboardDtos.RiskTrend;
import com.auditiq.dashboard.DashboardDtos.TransactionAnalysis;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the risk dashboard from the analytics views, so the in-app dashboard and Power BI report on identical
 * definitions. Each fact view is scanned once, using grouping sets for the breakdowns.
 */
@Service
public class DashboardService {

    static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    static final List<String> EXCEPTION_STATUSES = List.of("OPEN", "UNDER_INVESTIGATION", "CLOSED");
    static final List<String> INVESTIGATION_STATUSES =
            List.of("NEW", "UNDER_REVIEW", "AWAITING_EVIDENCE", "ESCALATED", "RESOLVED");
    static final int MAX_RANGE_DAYS = 3660;
    static final int TOP_ACCOUNTS = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public DashboardService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard build(LocalDate from, LocalDate to, boolean includeAccounts) {
        var none = new MapSqlParameterSource();
        Map<String, Object> bounds = jdbc.queryForMap("""
                SELECT (min(transaction_date) AT TIME ZONE 'Africa/Johannesburg')::date AS min_d,
                       (max(transaction_date) AT TIME ZONE 'Africa/Johannesburg')::date AS max_d
                FROM transactions""", none);
        LocalDate dataFrom = bounds.get("min_d") == null ? null : ((Date) bounds.get("min_d")).toLocalDate();
        LocalDate dataTo = bounds.get("max_d") == null ? null : ((Date) bounds.get("max_d")).toLocalDate();
        LocalDate start = from != null ? from : dataFrom != null ? dataFrom : LocalDate.now(clock);
        LocalDate end = to != null ? to : dataTo != null ? dataTo : LocalDate.now(clock);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("The date range may not exceed " + MAX_RANGE_DAYS + " days");
        }
        var range = new MapSqlParameterSource("from", Date.valueOf(start)).addValue("to", Date.valueOf(end));

        // -- transactions: one pass, all breakdowns ------------------------------------------------------------
        Map<String, List<Bucket>> tx = new LinkedHashMap<>();
        List<DailyPoint> daily = new ArrayList<>();
        Map<String, long[]> monthFlags = new LinkedHashMap<>();
        long[] totals = new long[5]; // count, flagged, rulesOnly, modelOnly, both
        BigDecimal[] totalValue = {BigDecimal.ZERO};
        jdbc.query("""
                SELECT CASE WHEN GROUPING(merchant_category) = 0 THEN 'category'
                            WHEN GROUPING(transaction_channel) = 0 THEN 'channel'
                            WHEN GROUPING(transaction_type) = 0 THEN 'type'
                            WHEN GROUPING(location) = 0 THEN 'location'
                            WHEN GROUPING(local_hour) = 0 THEN 'hour'
                            WHEN GROUPING(transaction_date) = 0 THEN 'day'
                            WHEN GROUPING(year_month) = 0 THEN 'month'
                            ELSE 'total' END AS dim,
                       coalesce(merchant_category, transaction_channel, transaction_type, location,
                                lpad(local_hour::text, 2, '0'), transaction_date::text, year_month) AS k,
                       count(*) AS c, coalesce(sum(amount), 0) AS v,
                       count(*) FILTER (WHERE is_flagged) AS flagged,
                       count(*) FILTER (WHERE rule_findings > 0 AND model_findings = 0) AS rules_only,
                       count(*) FILTER (WHERE model_findings > 0 AND rule_findings = 0) AS model_only,
                       count(*) FILTER (WHERE rule_findings > 0 AND model_findings > 0) AS both_detectors,
                       coalesce(sum(rule_findings), 0) AS rule_findings,
                       coalesce(sum(model_findings), 0) AS model_findings
                FROM (SELECT f.*, to_char(f.transaction_date, 'YYYY-MM') AS year_month
                      FROM analytics.fact_transactions f
                      WHERE f.transaction_date BETWEEN :from AND :to) f
                GROUP BY GROUPING SETS ((merchant_category), (transaction_channel), (transaction_type), (location),
                                        (local_hour), (transaction_date), (year_month), ())
                ORDER BY 1, 2
                """, range, rs -> {
                    String dim = rs.getString("dim");
                    String key = rs.getString("k");
                    long count = rs.getLong("c");
                    BigDecimal value = rs.getBigDecimal("v");
                    switch (dim) {
                        case "total" -> {
                            totals[0] = count;
                            totals[1] = rs.getLong("flagged");
                            totals[2] = rs.getLong("rules_only");
                            totals[3] = rs.getLong("model_only");
                            totals[4] = rs.getLong("both_detectors");
                            totalValue[0] = value;
                        }
                        case "day" -> daily.add(new DailyPoint(LocalDate.parse(key), count, value,
                                rs.getLong("flagged")));
                        case "month" -> {
                            tx.computeIfAbsent("month", d -> new ArrayList<>()).add(new Bucket(key, count, value));
                            monthFlags.put(key, new long[] {count, rs.getLong("flagged"), rs.getLong("rule_findings"),
                                    rs.getLong("model_findings")});
                        }
                        default -> tx.computeIfAbsent(dim, d -> new ArrayList<>())
                                .add(new Bucket(key == null ? "UNKNOWN" : key, count, value));
                    }
                });
        TransactionAnalysis transactions = new TransactionAnalysis(daily, tx.getOrDefault("month", List.of()),
                byValue(tx.get("category")), byCount(tx.get("channel")), byCount(tx.get("type")),
                byCount(tx.get("location")), tx.getOrDefault("hour", List.of()));

        // -- exceptions -----------------------------------------------------------------------------------------
        Map<String, Long> bySeverity = zeroFilled(SEVERITIES);
        Map<String, Long> byStatus = zeroFilled(EXCEPTION_STATUSES);
        List<Bucket> byCategory = new ArrayList<>();
        Map<String, long[]> byDetector = new LinkedHashMap<>();
        long[] exTotals = new long[2]; // exceptions, high priority
        jdbc.query("""
                SELECT CASE WHEN GROUPING(severity) = 0 THEN 'severity'
                            WHEN GROUPING(risk_category) = 0 THEN 'category'
                            WHEN GROUPING(detector_key) = 0 THEN 'detector'
                            WHEN GROUPING(exception_status) = 0 THEN 'status'
                            ELSE 'total' END AS dim,
                       coalesce(severity, risk_category, detector_key, exception_status) AS k,
                       count(*) AS c, count(*) FILTER (WHERE is_high_priority) AS high, coalesce(sum(amount), 0) AS v
                FROM analytics.fact_exceptions
                WHERE transaction_date BETWEEN :from AND :to
                GROUP BY GROUPING SETS ((severity), (risk_category), (detector_key), (exception_status), ())
                ORDER BY 1, 3 DESC
                """, range, rs -> {
                    String key = rs.getString("k");
                    long count = rs.getLong("c");
                    switch (rs.getString("dim")) {
                        case "severity" -> bySeverity.put(key, count);
                        case "status" -> byStatus.put(key, count);
                        case "category" -> byCategory.add(new Bucket(key, count, rs.getBigDecimal("v")));
                        case "detector" -> byDetector.put(key, new long[] {count, rs.getLong("high")});
                        default -> {
                            exTotals[0] = count;
                            exTotals[1] = rs.getLong("high");
                        }
                    }
                });
        List<DetectorCount> detectors = jdbc.query("""
                SELECT detector_key, detector_name, detector_type FROM analytics.dim_detector ORDER BY detector_key
                """, none, (rs, i) -> {
                    long[] c = byDetector.getOrDefault(rs.getString(1), new long[2]);
                    return new DetectorCount(rs.getString(1), rs.getString(2), rs.getString(3), c[0], c[1]);
                });

        List<RiskTrend> trend = monthFlags.entrySet().stream().map(e -> {
            long[] v = e.getValue();
            return new RiskTrend(e.getKey(), v[0], v[1], percent(v[1], v[0]), v[2], v[3]);
        }).toList();

        List<AccountRisk> topAccounts = includeAccounts ? jdbc.query("""
                SELECT f.account_id, a.account_type, a.branch, count(*) AS exceptions,
                       count(*) FILTER (WHERE f.detection_source = 'RULE') AS rule_findings,
                       count(*) FILTER (WHERE f.detection_source = 'ML') AS model_findings,
                       count(*) FILTER (WHERE f.is_high_priority) AS high_priority,
                       count(DISTINCT f.detector_key) AS detectors,
                       coalesce(sum(f.amount), 0) AS flagged_amount, max(f.anomaly_score) AS max_score,
                       count(*) FILTER (WHERE f.exception_status = 'OPEN') AS awaiting_review,
                       count(DISTINCT f.investigation_id) FILTER (WHERE f.investigation_status <> 'RESOLVED')
                           AS open_investigations
                FROM analytics.fact_exceptions f
                JOIN analytics.dim_account a ON a.account_id = f.account_id
                WHERE f.transaction_date BETWEEN :from AND :to
                GROUP BY f.account_id, a.account_type, a.branch
                ORDER BY high_priority DESC, detectors DESC, exceptions DESC, f.account_id
                LIMIT
                """ + TOP_ACCOUNTS, range, (rs, i) -> new AccountRisk(rs.getString("account_id"),
                rs.getString("account_type"), rs.getString("branch"), rs.getLong("exceptions"),
                rs.getLong("rule_findings"), rs.getLong("model_findings"), rs.getLong("high_priority"),
                rs.getLong("detectors"), rs.getBigDecimal("flagged_amount"), rs.getBigDecimal("max_score"),
                rs.getLong("awaiting_review"), rs.getLong("open_investigations"))) : null;

        RiskAnalysis risk = new RiskAnalysis(bySeverity, byCategory, detectors, byStatus, trend,
                new DetectorOverlap(totals[2], totals[3], totals[4]), topAccounts);

        // -- investigations (current workload, not date-filtered) -----------------------------------------------
        InvestigationMonitoring investigations = investigations(none);

        Executive executive = new Executive(totals[0], totalValue[0], exTotals[0], exTotals[1], totals[1],
                percent(totals[1], totals[0]), byStatus.get("OPEN"), investigations.open(), pipeline(none));
        return new Dashboard(clock.instant(), start, end, dataFrom, dataTo, executive, transactions, risk,
                investigations);
    }

    private InvestigationMonitoring investigations(MapSqlParameterSource none) {
        Map<String, Long> byStatus = zeroFilled(INVESTIGATION_STATUSES);
        jdbc.query("SELECT status, count(*) FROM analytics.fact_investigations GROUP BY status", none,
                rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT avg(resolution_hours) FILTER (WHERE NOT is_open) AS avg_resolution,
                       max(age_hours) FILTER (WHERE is_open) AS oldest_open,
                       (SELECT count(*) FROM audit_exceptions WHERE status = 'OPEN') AS awaiting
                FROM analytics.fact_investigations""", none);
        List<Bucket> byOutcome = jdbc.query("""
                SELECT outcome, count(*) FROM analytics.fact_investigations WHERE outcome IS NOT NULL
                GROUP BY outcome ORDER BY 2 DESC""", none, (rs, i) -> new Bucket(rs.getString(1), rs.getLong(2), null));
        List<Bucket> byAssignee = jdbc.query("""
                SELECT coalesce(assigned_to, 'Unassigned'), count(*) FROM analytics.fact_investigations WHERE is_open
                GROUP BY 1 ORDER BY 2 DESC""", none, (rs, i) -> new Bucket(rs.getString(1), rs.getLong(2), null));
        long resolved = byStatus.get("RESOLVED");
        long open = byStatus.values().stream().mapToLong(Long::longValue).sum() - resolved;
        long inProgress = byStatus.get("UNDER_REVIEW") + byStatus.get("AWAITING_EVIDENCE") + byStatus.get("ESCALATED");
        return new InvestigationMonitoring(byStatus, open, inProgress, resolved, hours(stats.get("avg_resolution")),
                hours(stats.get("oldest_open")), ((Number) stats.get("awaiting")).longValue(), byOutcome, byAssignee);
    }

    private Pipeline pipeline(MapSqlParameterSource none) {
        Map<String, Object> p = jdbc.queryForMap("""
                SELECT count(*) AS runs, count(*) FILTER (WHERE status = 'SUCCESS') AS ok,
                       (SELECT status FROM analytics.fact_pipeline_runs ORDER BY started_at DESC LIMIT 1) AS last_status,
                       (SELECT started_at FROM analytics.fact_pipeline_runs ORDER BY started_at DESC LIMIT 1) AS last_at,
                       (SELECT valid_rate_percent FROM analytics.fact_pipeline_runs
                        WHERE total_records IS NOT NULL ORDER BY started_at DESC LIMIT 1) AS last_valid
                FROM analytics.fact_pipeline_runs""", none);
        Timestamp lastAt = (Timestamp) p.get("last_at");
        return new Pipeline(((Number) p.get("runs")).longValue(), ((Number) p.get("ok")).longValue(),
                (String) p.get("last_status"), lastAt == null ? null : lastAt.toInstant(),
                (BigDecimal) p.get("last_valid"));
    }

    private static List<Bucket> byValue(List<Bucket> buckets) {
        if (buckets == null) {
            return List.of();
        }
        return buckets.stream().sorted((a, b) -> b.value().compareTo(a.value())).toList();
    }

    private static List<Bucket> byCount(List<Bucket> buckets) {
        if (buckets == null) {
            return List.of();
        }
        return buckets.stream().sorted((a, b) -> Long.compare(b.count(), a.count())).toList();
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part * 100.0 / whole).setScale(2, RoundingMode.HALF_UP);
    }

    private static Double hours(Object value) {
        return value == null ? null : Math.round(((Number) value).doubleValue() * 10) / 10.0;
    }

    private static Map<String, Long> zeroFilled(List<String> keys) {
        Map<String, Long> map = new LinkedHashMap<>();
        keys.forEach(k -> map.put(k, 0L));
        return map;
    }
}
