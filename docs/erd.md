# AuditIQ – Database ERD

Schema is managed by Flyway (`backend/src/main/resources/db/migration`). Monetary values use `NUMERIC(18,2)`; all timestamps are `TIMESTAMPTZ`.

## Data layers

| Schema | Purpose |
|---|---|
| `raw` | Data exactly as received (all text columns) + lineage (`pipeline_run_id`, `source_file`, `source_row`). |
| `staging` | Typed, standardised rows with validation status and rejection reasons. |
| `public` | Curated, normalised operational model used by the application. |
| `analytics` | Read-only views for the Angular dashboards and Power BI (Phase 5). |

## Curated model

```mermaid
erDiagram
    users ||--o{ user_roles : has
    roles ||--o{ user_roles : grants
    users ||--o{ refresh_tokens : owns
    users ||--o{ audit_logs : performs

    accounts ||--o{ transactions : "posts"
    merchants ||--o{ transactions : "receives"
    pipeline_runs ||--o{ transactions : "loaded by"
    pipeline_runs ||--|| data_quality_reports : produces

    audit_rules ||--o{ rule_executions : "executed as"
    audit_rules ||--o{ audit_exceptions : detects
    rule_executions ||--o{ audit_exceptions : "found in"
    transactions ||--o{ audit_exceptions : "flagged in"

    ml_model_versions ||--o{ anomaly_predictions : scores
    transactions ||--o{ anomaly_predictions : "scored as"
    anomaly_predictions ||--o{ audit_exceptions : "raises"

    audit_exceptions ||--o{ investigations : "investigated by"
    users ||--o{ investigations : "assigned / reviews"
    investigations ||--o{ investigation_notes : has
    investigations ||--o{ investigation_evidence : has
    investigations ||--o{ investigation_status_history : tracks

    policy_documents ||--o{ policy_chunks : "split into"
    users ||--o{ ai_interactions : asks
    audit_exceptions ||--o{ ai_interactions : "context for"

    users {
        bigint id PK
        varchar username UK
        varchar email UK
        varchar full_name
        varchar password_hash
        boolean enabled
        int failed_login_attempts
        timestamptz locked_until
        timestamptz last_login_at
    }
    roles { smallint id PK
        varchar name UK }
    user_roles { bigint user_id PK,FK
        smallint role_id PK,FK }
    refresh_tokens { bigint id PK
        bigint user_id FK
        char token_hash UK
        timestamptz expires_at
        boolean revoked }
    accounts {
        varchar account_id PK
        varchar customer_id
        varchar account_type
        varchar status
        date opened_date
        date last_activity_date
    }
    merchants {
        varchar merchant_id PK
        varchar merchant_name
        varchar merchant_category
        boolean is_vendor
    }
    transactions {
        varchar transaction_id PK
        varchar account_id FK
        varchar customer_id
        varchar merchant_id FK
        timestamptz transaction_date
        numeric transaction_amount
        char currency
        varchar transaction_type
        varchar transaction_channel
        varchar merchant_category
        varchar transaction_location
        varchar account_type
        varchar transaction_status
        numeric approval_threshold
        varchar approval_status
        bigint pipeline_run_id FK
    }
    audit_rules {
        varchar rule_id PK
        varchar rule_name
        text description
        text detection_logic
        varchar risk_category
        varchar severity
        jsonb thresholds
        boolean enabled
        timestamptz last_executed_at
    }
    rule_executions { bigint id PK
        varchar rule_id FK
        timestamptz started_at
        int exceptions_found
        varchar status }
    audit_exceptions {
        bigint id PK
        varchar exception_ref UK
        varchar detection_source
        varchar rule_id FK
        bigint prediction_id FK
        varchar transaction_id FK
        varchar severity
        varchar risk_category
        text reason
        jsonb details
        varchar status
    }
    ml_model_versions {
        bigint id PK
        varchar version UK
        jsonb hyperparameters
        jsonb metrics
        boolean is_active
        timestamptz trained_at
    }
    anomaly_predictions {
        bigint id PK
        varchar transaction_id FK
        bigint model_version_id FK
        numeric anomaly_score
        varchar priority
        jsonb explanation
    }
    investigations {
        bigint id PK
        varchar investigation_ref UK
        bigint exception_id FK
        varchar status
        bigint assigned_to FK
        bigint reviewed_by FK
        varchar outcome
        text outcome_summary
    }
    investigation_notes { bigint id PK
        bigint investigation_id FK
        bigint author_id FK
        text note }
    investigation_evidence { bigint id PK
        bigint investigation_id FK
        varchar evidence_type
        jsonb content
        boolean ai_generated
        varchar human_review_status }
    investigation_status_history { bigint id PK
        bigint investigation_id FK
        varchar from_status
        varchar to_status
        bigint changed_by FK }
    policy_documents { bigint id PK
        varchar document_code UK
        varchar title
        varchar version }
    policy_chunks { bigint id PK
        bigint document_id FK
        varchar section_title
        text content
        vector embedding }
    pipeline_runs { bigint id PK
        varchar run_ref UK
        varchar status
        varchar trigger_type
        bigint duration_ms }
    data_quality_reports { bigint id PK
        bigint pipeline_run_id FK
        int total_records
        int valid_records
        int rejected_records
        int duplicate_records
        jsonb missing_values }
    ai_interactions { bigint id PK
        bigint user_id FK
        varchar interaction_type
        jsonb sources
        jsonb tool_calls
        boolean grounded }
    audit_logs { bigint id PK
        timestamptz occurred_at
        bigint user_id FK
        varchar action
        varchar entity_type
        varchar entity_id
        jsonb details
        varchar outcome }
    system_settings { varchar setting_key PK
        text setting_value }
```

## Notes

* **Ground truth** for ML evaluation lives in `raw.synthetic_anomaly_labels` – deliberately separated from the curated `transactions` table so labels never leak into the features or the auditor UI.
* `policy_chunks.embedding` is created in the Phase 6 migration once the embedding dimension is fixed by the chosen provider.
* `audit_exceptions` has a partial unique index on `(rule_id, transaction_id)` so re-running a rule is idempotent.
* `investigations.version` provides optimistic locking for concurrent status changes.
