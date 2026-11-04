package com.auditiq;

import java.util.List;

import com.auditiq.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies migrations produce the intended structure and constraints. */
class DatabaseSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void rolesAreSeeded() {
        assertThat(jdbc.queryForList("SELECT name FROM roles", String.class))
                .containsExactlyInAnyOrder("ADMIN", "AUDITOR", "AUDIT_MANAGER");
    }

    @Test
    void dataLayerSchemasExist() {
        assertThat(jdbc.queryForList("SELECT schema_name FROM information_schema.schemata", String.class))
                .contains("raw", "staging", "public", "analytics");
    }

    @Test
    void pgvectorExtensionIsInstalled() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void monetaryColumnsUseExactDecimalTypes() {
        List<String> types = jdbc.queryForList("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name IN ('transaction_amount', 'approval_threshold')
                """, String.class);
        assertThat(types).isNotEmpty().allMatch("numeric"::equals);
    }

    @Test
    void requiredTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "roles", "user_roles", "accounts", "transactions", "merchants",
                "audit_rules", "audit_exceptions", "anomaly_predictions", "ml_model_versions", "investigations",
                "investigation_notes", "investigation_evidence", "policy_documents", "pipeline_runs",
                "data_quality_reports", "ai_interactions", "audit_logs");
    }

    @Test
    void negativeTransactionAmountIsRejected() {
        jdbc.update("""
                INSERT INTO accounts (account_id, customer_id, account_type, status, opened_date)
                VALUES ('ACC-T-1', 'CUS-T-1', 'CHEQUE', 'ACTIVE', DATE '2024-01-01') ON CONFLICT DO NOTHING
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transactions (transaction_id, account_id, customer_id, transaction_date, transaction_amount,
                    transaction_type, transaction_channel, account_type, transaction_status, approval_status)
                VALUES ('TX-NEG', 'ACC-T-1', 'CUS-T-1', now(), -1.00, 'DEBIT', 'ONLINE', 'CHEQUE', 'COMPLETED', 'N/A')
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidInvestigationStatusIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO investigations (investigation_ref, exception_id, title, status, priority, created_by)
                VALUES ('INV-X', 1, 't', 'CLOSED_BY_AI', 'LOW', 1)
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
