package com.auditiq.transaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.common.PageResponse;
import com.auditiq.transaction.TransactionDtos.AccountInfo;
import com.auditiq.transaction.TransactionDtos.AnomalyScore;
import com.auditiq.transaction.TransactionDtos.Bucket;
import com.auditiq.transaction.TransactionDtos.ExceptionRef;
import com.auditiq.transaction.TransactionDtos.Lineage;
import com.auditiq.transaction.TransactionDtos.MerchantInfo;
import com.auditiq.transaction.TransactionDtos.Totals;
import com.auditiq.transaction.TransactionDtos.TransactionAnalytics;
import com.auditiq.transaction.TransactionDtos.TransactionDetail;
import com.auditiq.transaction.TransactionDtos.TransactionFilter;
import com.auditiq.transaction.TransactionDtos.TransactionRow;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to the curated transaction layer. All filters are bound parameters; sort keys are whitelisted. */
@Service
public class TransactionService {

    /** Business dates are interpreted in the bank's local time zone. */
    public static final ZoneId BANK_ZONE = ZoneId.of("Africa/Johannesburg");

    static final Map<String, String> SORTS = Map.of(
            "transactionDate", "t.transaction_date",
            "amount", "t.transaction_amount",
            "accountId", "t.account_id");

    public static final String ROW_SELECT = """
            SELECT t.transaction_id, t.account_id, t.customer_id, t.transaction_date, t.transaction_amount, t.currency,
                   t.transaction_type, t.transaction_channel, t.merchant_id, m.merchant_name, t.merchant_category,
                   t.transaction_location, t.account_type, t.transaction_status, t.approval_threshold,
                   t.approval_status
            FROM transactions t LEFT JOIN merchants m ON m.merchant_id = t.merchant_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditLogService audit;

    public TransactionService(NamedParameterJdbcTemplate jdbc, AuditLogService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionRow> search(TransactionFilter filter, int page, int size, String sort,
                                               boolean ascending) {
        var params = new MapSqlParameterSource();
        String where = where(filter, params);
        long total = jdbc.queryForObject("SELECT count(*) FROM transactions t" + where, params, Long.class);
        String orderBy = SORTS.getOrDefault(sort, "t.transaction_date") + (ascending ? " ASC" : " DESC");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<TransactionRow> rows = jdbc.query(ROW_SELECT + where + " ORDER BY " + orderBy
                + ", t.transaction_id LIMIT :limit OFFSET :offset", params, ROW_MAPPER);
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public TransactionDetail detail(String transactionId) {
        var params = new MapSqlParameterSource("id", transactionId);
        TransactionRow row = jdbc.query(ROW_SELECT + " WHERE t.transaction_id = :id", params, ROW_MAPPER).stream()
                .findFirst().orElseThrow(() -> new NotFoundException("Transaction " + transactionId + " not found"));

        AccountInfo account = jdbc.queryForObject("""
                SELECT a.account_id, a.customer_id, a.account_type, a.status, a.branch, a.opened_date,
                       a.last_activity_date, s.cnt, s.avg_amount, s.first_at, s.last_at
                FROM accounts a,
                     LATERAL (SELECT count(*) AS cnt, round(avg(transaction_amount), 2) AS avg_amount,
                                     min(transaction_date) AS first_at, max(transaction_date) AS last_at
                              FROM transactions WHERE account_id = a.account_id) s
                WHERE a.account_id = :account
                """, new MapSqlParameterSource("account", row.accountId()), (rs, i) -> new AccountInfo(
                rs.getString("account_id"), rs.getString("customer_id"), rs.getString("account_type"),
                rs.getString("status"), rs.getString("branch"), date(rs, "opened_date"),
                date(rs, "last_activity_date"), rs.getLong("cnt"), rs.getBigDecimal("avg_amount"),
                instant(rs, "first_at"), instant(rs, "last_at")));

        MerchantInfo merchant = row.merchantId() == null ? null : jdbc.query("""
                SELECT merchant_id, merchant_name, merchant_category, city, is_vendor FROM merchants
                WHERE merchant_id = :m
                """, new MapSqlParameterSource("m", row.merchantId()), (rs, i) -> new MerchantInfo(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)))
                .stream().findFirst().orElse(null);

        Lineage lineage = jdbc.queryForObject("""
                SELECT t.pipeline_run_id, r.run_ref, t.loaded_at, t.original_amount, t.original_currency
                FROM transactions t LEFT JOIN pipeline_runs r ON r.id = t.pipeline_run_id
                WHERE t.transaction_id = :id
                """, params, (rs, i) -> new Lineage(rs.getObject("pipeline_run_id", Long.class),
                rs.getString("run_ref"), instant(rs, "loaded_at"), rs.getBigDecimal("original_amount"),
                rs.getString("original_currency")));

        List<ExceptionRef> exceptions = jdbc.query("""
                SELECT id, exception_ref, detection_source, rule_id, severity, risk_category, status, detected_at
                FROM audit_exceptions WHERE transaction_id = :id ORDER BY detected_at DESC
                """, params, (rs, i) -> new ExceptionRef(rs.getLong("id"), rs.getString("exception_ref"),
                rs.getString("detection_source"), rs.getString("rule_id"), rs.getString("severity"),
                rs.getString("risk_category"), rs.getString("status"), instant(rs, "detected_at")));

        AnomalyScore anomaly = jdbc.query("""
                SELECT p.anomaly_score, p.priority, p.is_anomaly, m.version FROM anomaly_predictions p
                JOIN ml_model_versions m ON m.id = p.model_version_id AND m.is_active
                WHERE p.transaction_id = :id
                """, params, (rs, i) -> new AnomalyScore(rs.getBigDecimal(1), rs.getString(2), rs.getBoolean(3),
                rs.getString(4))).stream().findFirst().orElse(null);

        audit.record(AuditAction.TRANSACTION_VIEWED, "TRANSACTION", transactionId, Map.of());
        return new TransactionDetail(row, account, merchant, lineage, exceptions, anomaly);
    }

    @Transactional(readOnly = true)
    public TransactionAnalytics analytics(LocalDate from, LocalDate to) {
        var params = new MapSqlParameterSource();
        String where = dateRange(from, to, params);

        Totals totals = jdbc.queryForObject("""
                SELECT count(*) AS cnt, coalesce(sum(transaction_amount), 0) AS val,
                       coalesce(round(avg(transaction_amount), 2), 0) AS avg_val,
                       count(DISTINCT account_id) AS accounts, count(DISTINCT merchant_id) AS merchants,
                       min(transaction_date) AS first_at, max(transaction_date) AS last_at
                FROM transactions t""" + where, params, (rs, i) -> new Totals(rs.getLong("cnt"),
                rs.getBigDecimal("val"), rs.getBigDecimal("avg_val"), rs.getLong("accounts"),
                rs.getLong("merchants"), instant(rs, "first_at"), instant(rs, "last_at")));

        String monthExpr = "to_char(date_trunc('month', t.transaction_date AT TIME ZONE 'Africa/Johannesburg'), 'YYYY-MM')";
        return new TransactionAnalytics(totals,
                buckets(monthExpr, where, params, "1 ASC", null),
                buckets("coalesce(t.merchant_category, 'NO_MERCHANT')", where, params, "3 DESC", null),
                buckets("t.transaction_channel", where, params, "2 DESC", null),
                buckets("t.transaction_type", where, params, "2 DESC", null),
                buckets("t.transaction_status", where, params, "2 DESC", null),
                buckets("t.transaction_location", where, params, "2 DESC", 12));
    }

    private List<Bucket> buckets(String keyExpr, String where, MapSqlParameterSource params, String order,
                                 Integer limit) {
        String sql = "SELECT " + keyExpr + " AS k, count(*) AS c, sum(t.transaction_amount) AS v FROM transactions t"
                + where + " GROUP BY 1 ORDER BY " + order + (limit == null ? "" : " LIMIT " + limit);
        return jdbc.query(sql, params, (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("c"),
                rs.getBigDecimal("v")));
    }

    private static String where(TransactionFilter f, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        eq(clauses, params, "t.account_id", "accountId", upper(f.accountId()));
        eq(clauses, params, "t.customer_id", "customerId", upper(f.customerId()));
        eq(clauses, params, "t.merchant_id", "merchantId", upper(f.merchantId()));
        eq(clauses, params, "t.transaction_type", "type", upper(f.type()));
        eq(clauses, params, "t.transaction_channel", "channel", upper(f.channel()));
        eq(clauses, params, "t.merchant_category", "category", upper(f.category()));
        eq(clauses, params, "t.transaction_status", "status", upper(f.status()));
        eq(clauses, params, "t.approval_status", "approvalStatus", upper(f.approvalStatus()));
        if (f.q() != null && !f.q().isBlank()) {
            clauses.add("t.transaction_id LIKE :q");
            params.addValue("q", f.q().trim().toUpperCase().replace("%", "").replace("_", "\\_") + "%");
        }
        if (f.minAmount() != null) {
            clauses.add("t.transaction_amount >= :minAmount");
            params.addValue("minAmount", f.minAmount());
        }
        if (f.maxAmount() != null) {
            clauses.add("t.transaction_amount <= :maxAmount");
            params.addValue("maxAmount", f.maxAmount());
        }
        String dates = dateRange(f.from(), f.to(), params);
        if (!dates.isEmpty()) {
            clauses.add(dates.substring(" WHERE ".length()));
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static String dateRange(LocalDate from, LocalDate to, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        if (from != null) {
            clauses.add("t.transaction_date >= :fromTs");
            params.addValue("fromTs", Timestamp.from(from.atStartOfDay(BANK_ZONE).toInstant()));
        }
        if (to != null) {
            clauses.add("t.transaction_date < :toTs");
            params.addValue("toTs", Timestamp.from(to.plusDays(1).atStartOfDay(BANK_ZONE).toInstant()));
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static void eq(List<String> clauses, MapSqlParameterSource params, String column, String name,
                           String value) {
        if (value != null) {
            clauses.add(column + " = :" + name);
            params.addValue(name, value);
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    public static final RowMapper<TransactionRow> ROW_MAPPER = (rs, i) -> new TransactionRow(
            rs.getString("transaction_id"), rs.getString("account_id"), rs.getString("customer_id"),
            instant(rs, "transaction_date"), rs.getBigDecimal("transaction_amount"), rs.getString("currency"),
            rs.getString("transaction_type"), rs.getString("transaction_channel"), rs.getString("merchant_id"),
            rs.getString("merchant_name"), rs.getString("merchant_category"), rs.getString("transaction_location"),
            rs.getString("account_type"), rs.getString("transaction_status"), rs.getBigDecimal("approval_threshold"),
            rs.getString("approval_status"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
