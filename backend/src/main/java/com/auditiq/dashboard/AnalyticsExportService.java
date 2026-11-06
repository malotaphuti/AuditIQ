package com.auditiq.dashboard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.NotFoundException;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV extracts of the analytics views, for Power BI or other tools that work from exported datasets. Only whitelisted
 * views can be exported; text cells that a spreadsheet could interpret as a formula are neutralised.
 */
@Service
public class AnalyticsExportService {

    /** dataset name -> view and stable ordering. */
    static final Map<String, String[]> DATASETS = new LinkedHashMap<>();

    static {
        DATASETS.put("fact_transactions", new String[] {"analytics.fact_transactions", "transaction_id"});
        DATASETS.put("fact_exceptions", new String[] {"analytics.fact_exceptions", "exception_id"});
        DATASETS.put("fact_investigations", new String[] {"analytics.fact_investigations", "investigation_id"});
        DATASETS.put("fact_pipeline_runs", new String[] {"analytics.fact_pipeline_runs", "run_id"});
        DATASETS.put("fact_model_versions", new String[] {"analytics.fact_model_versions", "trained_at"});
        DATASETS.put("dim_account", new String[] {"analytics.dim_account", "account_id"});
        DATASETS.put("dim_merchant", new String[] {"analytics.dim_merchant", "merchant_id"});
        DATASETS.put("dim_detector", new String[] {"analytics.dim_detector", "detector_key"});
        DATASETS.put("dim_date", new String[] {"analytics.dim_date", "date_key"});
        DATASETS.put("account_risk", new String[] {"analytics.account_risk", "account_id"});
    }

    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public AnalyticsExportService(DataSource dataSource, AuditLogService audit) {
        // Dedicated template so the streaming fetch size never affects other queries.
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(2_000);
        this.audit = audit;
    }

    public static boolean exists(String dataset) {
        return DATASETS.containsKey(dataset);
    }

    @Transactional(readOnly = true)
    public long export(String dataset, Writer out) {
        String[] source = DATASETS.get(dataset);
        if (source == null) {
            throw new NotFoundException("Unknown dataset " + dataset);
        }
        long[] rows = {0};
        boolean[] header = {false};
        jdbc.query("SELECT * FROM " + source[0] + " ORDER BY " + source[1], rs -> {
            try {
                if (!header[0]) {
                    writeHeader(rs.getMetaData(), out);
                    header[0] = true;
                }
                writeRow(rs, out);
                rows[0]++;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        try {
            if (!header[0]) {
                // Empty dataset: still emit the column names.
                jdbc.query("SELECT * FROM " + source[0] + " LIMIT 0", (ResultSet rs) -> {
                    try {
                        writeHeader(rs.getMetaData(), out);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    return null;
                });
            }
            out.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(AuditAction.ANALYTICS_EXPORTED, "ANALYTICS_DATASET", dataset, Map.of("rows", rows[0]));
        return rows[0];
    }

    private static void writeHeader(ResultSetMetaData meta, Writer out) throws SQLException, IOException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (i > 1) {
                out.write(',');
            }
            out.write(meta.getColumnLabel(i));
        }
        out.write("\r\n");
    }

    private static void writeRow(ResultSet rs, Writer out) throws SQLException, IOException {
        int columns = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                out.write(',');
            }
            Object value = rs.getObject(i);
            if (value instanceof Timestamp ts) {
                out.write(ts.toInstant().toString());
            } else if (value instanceof Number || value instanceof Boolean) {
                out.write(value.toString());
            } else if (value != null) {
                out.write(cell(value.toString()));
            }
        }
        out.write("\r\n");
    }

    /** Quotes when needed and neutralises spreadsheet formula prefixes (CSV injection). */
    static String cell(String text) {
        String safe = !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
