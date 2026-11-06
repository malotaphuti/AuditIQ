package com.auditiq.dashboard;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.auditiq.audit.AuditLogRepository;
import com.auditiq.bi.BiProperties;
import com.auditiq.bi.BiReaderInitializer;
import com.auditiq.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dashboard figures, exports and the read-only reporting login against the real analytics views. */
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static final String FROM = "2026-02-01";
    private static final String TO = "2026-02-28";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO accounts (account_id, customer_id, account_type, status, branch, opened_date)
                VALUES ('ACC-DB-001', 'CUS-DB-001', 'BUSINESS', 'ACTIVE', 'Durban', DATE '2020-01-01')
                ON CONFLICT DO NOTHING""");
        jdbc.update("""
                INSERT INTO merchants (merchant_id, merchant_name, merchant_category, is_vendor)
                VALUES ('MER-DB-01', '=HYPERLINK("http://example.invalid")', 'IT_SERVICES', true)
                ON CONFLICT DO NOTHING""");
        String tx = """
                INSERT INTO transactions (transaction_id, account_id, customer_id, merchant_id, transaction_date,
                    transaction_amount, transaction_type, transaction_channel, merchant_category, transaction_location,
                    account_type, transaction_status, approval_threshold, approval_status)
                VALUES (?, 'ACC-DB-001', 'CUS-DB-001', 'MER-DB-01', ?::timestamptz, ?, 'VENDOR_PAYMENT', 'EFT',
                        'IT_SERVICES', 'Durban', 'BUSINESS', 'COMPLETED', 250000, 'NOT_REQUIRED')
                ON CONFLICT DO NOTHING""";
        jdbc.update(tx, "TXNDB0000001", "2026-02-10T08:00:00Z", 120000.00);
        jdbc.update(tx, "TXNDB0000002", "2026-02-10T08:05:00Z", 120000.00);
        jdbc.update(tx, "TXNDB0000003", "2026-02-11T21:30:00Z", 9000.00);
        jdbc.update("""
                INSERT INTO audit_exceptions (exception_ref, detection_source, rule_id, transaction_id, account_id,
                                              severity, risk_category, reason)
                VALUES ('EXC-DB-1', 'RULE', 'AR-001', 'TXNDB0000002', 'ACC-DB-001', 'HIGH', 'PAYMENT_INTEGRITY',
                        'Same account, amount and date.')
                ON CONFLICT DO NOTHING""");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void dashboardFiguresComeFromTheAnalyticsLayer() throws Exception {
        long transactions = count("SELECT count(*) FROM analytics.fact_transactions WHERE transaction_date BETWEEN '"
                + FROM + "' AND '" + TO + "'");
        long exceptions = count("SELECT count(*) FROM analytics.fact_exceptions WHERE transaction_date BETWEEN '"
                + FROM + "' AND '" + TO + "'");
        JsonNode d = json.readTree(mvc.perform(get("/api/analytics/dashboard").param("from", FROM).param("to", TO)
                        .header("Authorization", managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.risk.topAccounts[*].accountId", hasItem("ACC-DB-001")))
                .andExpect(jsonPath("$.risk.byDetector[6].key").value("ML"))
                .andExpect(jsonPath("$.investigations.byStatus.NEW").isNumber())
                .andReturn().getResponse().getContentAsString());
        assertThat(d.at("/executive/transactions").asLong()).isEqualTo(transactions).isGreaterThanOrEqualTo(3);
        assertThat(d.at("/executive/potentialExceptions").asLong()).isEqualTo(exceptions);
        assertThat(d.at("/risk/bySeverity/HIGH").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(d.at("/risk/ruleVsModel/rulesOnly").asLong()).isGreaterThanOrEqualTo(1);
        long dailyTotal = 0;
        for (JsonNode p : d.at("/transactions/daily")) {
            dailyTotal += p.get("count").asLong();
        }
        assertThat(dailyTotal).isEqualTo(transactions);
        assertThat(d.at("/transactions/byChannel")).anySatisfy(b -> assertThat(b.get("key").asText()).isEqualTo("EFT"));
        assertThat(d.at("/risk/monthlyTrend/0/month").asText()).isEqualTo("2026-02");
    }

    @Test
    void administratorsSeeAggregatesButNoAccountLevelDetail() throws Exception {
        mvc.perform(get("/api/analytics/dashboard").header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executive.transactions").isNumber())
                .andExpect(jsonPath("$.risk.topAccounts").doesNotExist());
    }

    @Test
    void dateRangesAreValidated() throws Exception {
        String token = auditorToken();
        mvc.perform(get("/api/analytics/dashboard").param("from", "2026-03-01").param("to", "2026-02-01")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics/dashboard").param("from", "2000-01-01").param("to", "2026-02-01")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics/dashboard").param("from", "yesterday").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void datasetsExportAsSafeCsvAndAreAudited() throws Exception {
        String token = auditorToken();
        mvc.perform(get("/api/analytics/datasets").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("fact_exceptions")));
        String csv = mvc.perform(get("/api/analytics/export/dim_merchant").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("auditiq_dim_merchant_")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("merchant_id,merchant_name,merchant_category,city,country,is_vendor\r\n");
        // Formula-like text is neutralised and quoted.
        assertThat(csv).contains("MER-DB-01,\"'=HYPERLINK(\"\"http://example.invalid\"\")\",IT_SERVICES");
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("ANALYTICS_EXPORTED"))
                .anySatisfy(l -> assertThat(l.getEntityId()).isEqualTo("dim_merchant"));

        mvc.perform(get("/api/analytics/export/users").header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/analytics/export/fact_exceptions").header("Authorization", adminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void csvCellsAreEscaped() {
        assertThat(AnalyticsExportService.cell("plain")).isEqualTo("plain");
        assertThat(AnalyticsExportService.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AnalyticsExportService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AnalyticsExportService.cell("+27 11")).isEqualTo("'+27 11");
        assertThat(AnalyticsExportService.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void reportingLoginCanReadOnlyTheAnalyticsSchema() throws SQLException {
        String password = "Reporting-Only-Passw0rd!";
        new BiReaderInitializer(jdbc, new BiProperties("auditiq_bi_it", password)).run(null);
        try (Connection bi = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "auditiq_bi_it", password)) {
            try (var rs = bi.createStatement().executeQuery("SELECT count(*) FROM analytics.fact_exceptions")) {
                assertThat(rs.next()).isTrue();
            }
            assertThatThrownBy(() -> bi.createStatement().executeQuery("SELECT * FROM public.users"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> bi.createStatement().executeQuery("SELECT * FROM public.audit_logs"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> bi.createStatement().executeUpdate("CREATE TABLE analytics.x (id int)"))
                    .isInstanceOf(SQLException.class);
        }
        // Weak passwords and invalid names are refused (no login created).
        new BiReaderInitializer(jdbc, new BiProperties("bad name; drop", password)).run(null);
        new BiReaderInitializer(jdbc, new BiProperties("auditiq_bi_weak", "short")).run(null);
        assertThat(count("SELECT count(*) FROM pg_roles WHERE rolname IN ('auditiq_bi_weak')")).isZero();
    }
}
