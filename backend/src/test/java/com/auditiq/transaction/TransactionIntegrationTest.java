package com.auditiq.transaction;

import com.auditiq.audit.AuditLogRepository;
import com.auditiq.support.AbstractIntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransactionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO accounts (account_id, customer_id, account_type, status, branch, opened_date,
                                      last_activity_date)
                VALUES ('ACC-IT-0001', 'CUS-IT-0001', 'CHEQUE', 'ACTIVE', 'Durban', DATE '2020-01-01', DATE '2025-10-20')
                ON CONFLICT DO NOTHING""");
        jdbc.update("""
                INSERT INTO merchants (merchant_id, merchant_name, merchant_category, city, is_vendor)
                VALUES ('MER-IT-01', 'Protea Grocer', 'GROCERIES', 'Durban', false) ON CONFLICT DO NOTHING""");
        String insert = """
                INSERT INTO transactions (transaction_id, account_id, customer_id, merchant_id, transaction_date,
                    transaction_amount, currency, original_amount, original_currency, transaction_type,
                    transaction_channel, merchant_category, transaction_location, account_type, transaction_status,
                    approval_threshold, approval_status)
                VALUES (?, 'ACC-IT-0001', 'CUS-IT-0001', ?, ?::timestamptz, ?, 'ZAR', ?, ?, ?, ?, ?, 'Durban',
                        'CHEQUE', 'COMPLETED', 50000, 'NOT_REQUIRED')
                ON CONFLICT DO NOTHING""";
        jdbc.update(insert, "TXNIT0000001", "MER-IT-01", "2026-03-01T08:00:00Z", 450.25, 450.25, "ZAR", "PURCHASE",
                "POS", "GROCERIES");
        jdbc.update(insert, "TXNIT0000002", null, "2026-03-02T08:00:00Z", 12000.00, 12000.00, "ZAR", "TRANSFER",
                "ONLINE", null);
        jdbc.update(insert, "TXNIT0000003", "MER-IT-01", "2026-04-15T21:30:00Z", 182.00, 10.00, "USD", "PURCHASE",
                "ONLINE", "GROCERIES");
    }

    @Test
    void auditorCanFilterByAccountAndSortByAmount() throws Exception {
        mvc.perform(get("/api/transactions").param("accountId", "acc-it-0001").param("sort", "amount")
                        .param("direction", "asc").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].transactionId").value("TXNIT0000003"))
                .andExpect(jsonPath("$.content[2].transactionId").value("TXNIT0000002"))
                .andExpect(jsonPath("$.content[1].merchantName").value("Protea Grocer"));
    }

    @Test
    void filtersCombine() throws Exception {
        String token = managerToken();
        mvc.perform(get("/api/transactions").param("accountId", "ACC-IT-0001").param("channel", "online")
                        .param("type", "PURCHASE").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionId").value("TXNIT0000003"));
        mvc.perform(get("/api/transactions").param("accountId", "ACC-IT-0001").param("minAmount", "1000")
                        .header("Authorization", token))
                .andExpect(jsonPath("$.totalElements").value(1));
        // Business-date filter in bank local time: 2026-04-15 21:30Z is 23:30 SAST on the 15th.
        mvc.perform(get("/api/transactions").param("accountId", "ACC-IT-0001").param("from", "2026-04-15")
                        .param("to", "2026-04-15").header("Authorization", token))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/transactions").param("q", "TXNIT00000").param("size", "2")
                        .header("Authorization", token))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        String token = auditorToken();
        mvc.perform(get("/api/transactions").param("size", "1000").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions").param("sort", "password_hash").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions").param("accountId", "x' OR '1'='1").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions").param("from", "2026-05-01").param("to", "2026-04-01")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions").param("from", "not-a-date").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void administratorsCannotBrowseTransactionLevelData() throws Exception {
        mvc.perform(get("/api/transactions").header("Authorization", adminToken())).andExpect(status().isForbidden());
        mvc.perform(get("/api/transactions/TXNIT0000001").header("Authorization", adminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailIncludesAccountMerchantLineageAndIsAudited() throws Exception {
        mvc.perform(get("/api/transactions/{id}", "txnit0000003").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.amount").value(182.00))
                .andExpect(jsonPath("$.lineage.originalAmount").value(10.00))
                .andExpect(jsonPath("$.lineage.originalCurrency").value("USD"))
                .andExpect(jsonPath("$.account.branch").value("Durban"))
                .andExpect(jsonPath("$.account.transactionCount").value(3))
                .andExpect(jsonPath("$.merchant.merchantName").value("Protea Grocer"))
                .andExpect(jsonPath("$.exceptions").isArray());
        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("TRANSACTION_VIEWED"))
                .anySatisfy(l -> assertThat(l.getEntityId()).isEqualTo("TXNIT0000003"));
    }

    @Test
    void unknownTransactionIs404() throws Exception {
        mvc.perform(get("/api/transactions/TXNDOESNOTEXIST").header("Authorization", auditorToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void analyticsAggregatesTheCuratedLayer() throws Exception {
        mvc.perform(get("/api/transactions/analytics").param("from", "2026-03-01").param("to", "2026-04-30")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.count", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.monthly[0].key").value("2026-03"))
                .andExpect(jsonPath("$.byChannel").isArray())
                .andExpect(jsonPath("$.byCategory").isArray());
    }
}
