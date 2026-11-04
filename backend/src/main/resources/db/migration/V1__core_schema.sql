-- =====================================================================
-- AuditIQ core schema
-- Layers: raw -> staging -> public (curated) -> analytics (views)
-- Monetary values: NUMERIC(18,2). Timestamps: TIMESTAMPTZ.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS raw;
CREATE SCHEMA IF NOT EXISTS staging;
CREATE SCHEMA IF NOT EXISTS analytics;

-- ---------------------------------------------------------------------
-- Identity & access
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    id          SMALLSERIAL PRIMARY KEY,
    name        VARCHAR(32)  NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    username              VARCHAR(50)  NOT NULL UNIQUE,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    full_name             VARCHAR(120) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id BIGINT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64)    NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- ---------------------------------------------------------------------
-- Pipeline monitoring
-- ---------------------------------------------------------------------
CREATE TABLE pipeline_runs (
    id            BIGSERIAL PRIMARY KEY,
    run_ref       VARCHAR(40)  NOT NULL UNIQUE,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    trigger_type  VARCHAR(20)  NOT NULL CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    triggered_by  VARCHAR(50),
    source        VARCHAR(255),
    parameters    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    duration_ms   BIGINT,
    error_message TEXT
);
CREATE INDEX idx_pipeline_runs_started ON pipeline_runs (started_at DESC);

CREATE TABLE data_quality_reports (
    id                     BIGSERIAL PRIMARY KEY,
    pipeline_run_id        BIGINT      NOT NULL UNIQUE REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    total_records          INT         NOT NULL,
    valid_records          INT         NOT NULL,
    rejected_records       INT         NOT NULL,
    duplicate_records      INT         NOT NULL,
    missing_value_total    INT         NOT NULL,
    missing_values         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    rejection_reasons      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    processing_duration_ms BIGINT      NOT NULL,
    status                 VARCHAR(20) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Raw & staging layers (written by the Python pipeline)
-- ---------------------------------------------------------------------
CREATE TABLE raw.transactions_raw (
    id                   BIGSERIAL PRIMARY KEY,
    pipeline_run_id      BIGINT      NOT NULL REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    source_file          VARCHAR(255),
    source_row           INT,
    transaction_id       TEXT,
    account_id           TEXT,
    customer_id          TEXT,
    transaction_date     TEXT,
    transaction_amount   TEXT,
    currency             TEXT,
    transaction_type     TEXT,
    transaction_channel  TEXT,
    merchant_id          TEXT,
    merchant_category    TEXT,
    transaction_location TEXT,
    account_type         TEXT,
    transaction_status   TEXT,
    approval_threshold   TEXT,
    approval_status      TEXT,
    ingested_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_raw_tx_run ON raw.transactions_raw (pipeline_run_id);

-- Ground-truth labels of injected anomalies, used ONLY for model evaluation.
CREATE TABLE raw.synthetic_anomaly_labels (
    transaction_id VARCHAR(40) PRIMARY KEY,
    anomaly_type   VARCHAR(40) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE staging.transactions_stg (
    id                   BIGSERIAL PRIMARY KEY,
    pipeline_run_id      BIGINT      NOT NULL REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    raw_id               BIGINT,
    transaction_id       VARCHAR(40),
    account_id           VARCHAR(20),
    customer_id          VARCHAR(20),
    transaction_date     TIMESTAMPTZ,
    transaction_amount   NUMERIC(18, 2),
    currency             VARCHAR(3),
    transaction_type     VARCHAR(30),
    transaction_channel  VARCHAR(30),
    merchant_id          VARCHAR(20),
    merchant_category    VARCHAR(50),
    transaction_location VARCHAR(80),
    account_type         VARCHAR(30),
    transaction_status   VARCHAR(20),
    approval_threshold   NUMERIC(18, 2),
    approval_status      VARCHAR(20),
    validation_status    VARCHAR(10) NOT NULL CHECK (validation_status IN ('VALID', 'REJECTED', 'DUPLICATE')),
    rejection_reasons    TEXT[]      NOT NULL DEFAULT '{}',
    processed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stg_tx_run_status ON staging.transactions_stg (pipeline_run_id, validation_status);

-- ---------------------------------------------------------------------
-- Curated banking model
-- ---------------------------------------------------------------------
CREATE TABLE accounts (
    account_id         VARCHAR(20) PRIMARY KEY,
    customer_id        VARCHAR(20) NOT NULL,
    account_type       VARCHAR(30) NOT NULL,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'DORMANT', 'CLOSED')),
    branch             VARCHAR(80),
    currency           VARCHAR(3)     NOT NULL DEFAULT 'ZAR',
    opened_date        DATE        NOT NULL,
    last_activity_date DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_accounts_customer ON accounts (customer_id);

CREATE TABLE merchants (
    merchant_id       VARCHAR(20) PRIMARY KEY,
    merchant_name     VARCHAR(120) NOT NULL,
    merchant_category VARCHAR(50)  NOT NULL,
    city              VARCHAR(80),
    country           VARCHAR(2)      NOT NULL DEFAULT 'ZA',
    is_vendor         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    transaction_id       VARCHAR(40) PRIMARY KEY,
    account_id           VARCHAR(20)    NOT NULL REFERENCES accounts (account_id),
    customer_id          VARCHAR(20)    NOT NULL,
    merchant_id          VARCHAR(20) REFERENCES merchants (merchant_id),
    transaction_date     TIMESTAMPTZ    NOT NULL,
    transaction_amount   NUMERIC(18, 2) NOT NULL CHECK (transaction_amount >= 0),
    currency             VARCHAR(3)        NOT NULL DEFAULT 'ZAR',
    transaction_type     VARCHAR(30)    NOT NULL,
    transaction_channel  VARCHAR(30)    NOT NULL,
    merchant_category    VARCHAR(50),
    transaction_location VARCHAR(80),
    account_type         VARCHAR(30)    NOT NULL,
    transaction_status   VARCHAR(20)    NOT NULL,
    approval_threshold   NUMERIC(18, 2),
    approval_status      VARCHAR(20)    NOT NULL,
    pipeline_run_id      BIGINT REFERENCES pipeline_runs (id),
    loaded_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_tx_account_date ON transactions (account_id, transaction_date);
CREATE INDEX idx_tx_date ON transactions (transaction_date);
CREATE INDEX idx_tx_merchant_date ON transactions (merchant_id, transaction_date);
CREATE INDEX idx_tx_amount ON transactions (transaction_amount);

-- ---------------------------------------------------------------------
-- Audit rules & exceptions
-- ---------------------------------------------------------------------
CREATE TABLE audit_rules (
    rule_id          VARCHAR(20) PRIMARY KEY,
    rule_name        VARCHAR(120) NOT NULL,
    description      TEXT         NOT NULL,
    detection_logic  TEXT         NOT NULL,
    risk_category    VARCHAR(50)  NOT NULL,
    severity         VARCHAR(10)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    thresholds       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    version          INT          NOT NULL DEFAULT 1,
    last_executed_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE rule_executions (
    id               BIGSERIAL PRIMARY KEY,
    rule_id          VARCHAR(20) NOT NULL REFERENCES audit_rules (rule_id),
    thresholds_used  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    exceptions_found INT         NOT NULL DEFAULT 0,
    executed_by      VARCHAR(50),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    error_message    TEXT
);
CREATE INDEX idx_rule_exec_rule ON rule_executions (rule_id, started_at DESC);

CREATE TABLE ml_model_versions (
    id               BIGSERIAL PRIMARY KEY,
    model_name       VARCHAR(80)  NOT NULL,
    version          VARCHAR(40)  NOT NULL UNIQUE,
    algorithm        VARCHAR(80)  NOT NULL,
    hyperparameters  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    feature_list     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    priority_thresholds JSONB     NOT NULL DEFAULT '{}'::jsonb,
    training_rows    INT          NOT NULL,
    metrics          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    artifact_path    VARCHAR(255),
    is_active        BOOLEAN      NOT NULL DEFAULT FALSE,
    trained_by       VARCHAR(50),
    trained_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_ml_model_active ON ml_model_versions (model_name) WHERE is_active;

CREATE TABLE anomaly_predictions (
    id               BIGSERIAL PRIMARY KEY,
    transaction_id   VARCHAR(40)    NOT NULL REFERENCES transactions (transaction_id) ON DELETE CASCADE,
    model_version_id BIGINT         NOT NULL REFERENCES ml_model_versions (id) ON DELETE CASCADE,
    anomaly_score    NUMERIC(10, 6) NOT NULL,
    is_anomaly       BOOLEAN        NOT NULL,
    priority         VARCHAR(10) CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    explanation      JSONB          NOT NULL DEFAULT '{}'::jsonb,
    predicted_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (transaction_id, model_version_id)
);
CREATE INDEX idx_pred_model_score ON anomaly_predictions (model_version_id, anomaly_score DESC);

CREATE TABLE audit_exceptions (
    id                BIGSERIAL PRIMARY KEY,
    exception_ref     VARCHAR(30) NOT NULL UNIQUE,
    detection_source  VARCHAR(10) NOT NULL CHECK (detection_source IN ('RULE', 'ML')),
    rule_id           VARCHAR(20) REFERENCES audit_rules (rule_id),
    rule_execution_id BIGINT REFERENCES rule_executions (id),
    prediction_id     BIGINT REFERENCES anomaly_predictions (id) ON DELETE SET NULL,
    transaction_id    VARCHAR(40) NOT NULL REFERENCES transactions (transaction_id) ON DELETE CASCADE,
    account_id        VARCHAR(20) NOT NULL REFERENCES accounts (account_id),
    severity          VARCHAR(10) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    risk_category     VARCHAR(50) NOT NULL,
    reason            TEXT        NOT NULL,
    details           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status            VARCHAR(25) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'UNDER_INVESTIGATION', 'CLOSED')),
    detected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((detection_source = 'RULE' AND rule_id IS NOT NULL) OR (detection_source = 'ML'))
);
CREATE UNIQUE INDEX uq_exception_rule_tx ON audit_exceptions (rule_id, transaction_id) WHERE detection_source = 'RULE';
CREATE UNIQUE INDEX uq_exception_ml_tx ON audit_exceptions (transaction_id) WHERE detection_source = 'ML';
CREATE INDEX idx_exceptions_filter ON audit_exceptions (severity, risk_category, detected_at DESC);
CREATE INDEX idx_exceptions_account ON audit_exceptions (account_id);
CREATE INDEX idx_exceptions_status ON audit_exceptions (status);

-- ---------------------------------------------------------------------
-- Investigations
-- ---------------------------------------------------------------------
CREATE TABLE investigations (
    id                BIGSERIAL PRIMARY KEY,
    investigation_ref VARCHAR(30)  NOT NULL UNIQUE,
    exception_id      BIGINT       NOT NULL REFERENCES audit_exceptions (id),
    title             VARCHAR(200) NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'UNDER_REVIEW', 'AWAITING_EVIDENCE', 'ESCALATED', 'RESOLVED')),
    priority          VARCHAR(10)  NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    assigned_to       BIGINT REFERENCES users (id),
    created_by        BIGINT       NOT NULL REFERENCES users (id),
    outcome           VARCHAR(40)
        CHECK (outcome IN ('NO_EXCEPTION', 'CONTROL_WEAKNESS', 'POLICY_BREACH', 'DATA_QUALITY_ISSUE', 'REFERRED_FOR_FURTHER_REVIEW')),
    outcome_summary   TEXT,
    reviewed_by       BIGINT REFERENCES users (id),
    reviewed_at       TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    version           INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_open_investigation_per_exception ON investigations (exception_id) WHERE status <> 'RESOLVED';
CREATE INDEX idx_investigations_status ON investigations (status);
CREATE INDEX idx_investigations_assignee ON investigations (assigned_to);

CREATE TABLE investigation_notes (
    id               BIGSERIAL PRIMARY KEY,
    investigation_id BIGINT      NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    author_id        BIGINT      NOT NULL REFERENCES users (id),
    note             TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inv_notes_inv ON investigation_notes (investigation_id);

CREATE TABLE investigation_evidence (
    id                  BIGSERIAL PRIMARY KEY,
    investigation_id    BIGINT      NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    evidence_type       VARCHAR(30) NOT NULL CHECK (evidence_type IN ('TRANSACTION', 'POLICY_EXCERPT', 'AI_REPORT', 'RULE_RESULT', 'MODEL_RESULT', 'DOCUMENT')),
    reference_id        VARCHAR(80),
    description         VARCHAR(500) NOT NULL,
    content             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ai_generated        BOOLEAN     NOT NULL DEFAULT FALSE,
    human_review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED'
        CHECK (human_review_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by         BIGINT REFERENCES users (id),
    reviewed_at         TIMESTAMPTZ,
    added_by            BIGINT REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inv_evidence_inv ON investigation_evidence (investigation_id);

CREATE TABLE investigation_status_history (
    id               BIGSERIAL PRIMARY KEY,
    investigation_id BIGINT      NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20) NOT NULL,
    changed_by       BIGINT      NOT NULL REFERENCES users (id),
    reason           TEXT,
    changed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inv_history_inv ON investigation_status_history (investigation_id);

-- ---------------------------------------------------------------------
-- Knowledge base (embeddings table added in the RAG phase)
-- ---------------------------------------------------------------------
CREATE TABLE policy_documents (
    id             BIGSERIAL PRIMARY KEY,
    document_code  VARCHAR(30)  NOT NULL UNIQUE,
    title          VARCHAR(200) NOT NULL,
    version        VARCHAR(20)  NOT NULL,
    owner          VARCHAR(120),
    file_name      VARCHAR(255) NOT NULL,
    content_hash   VARCHAR(64)     NOT NULL,
    effective_date DATE,
    ingested_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Governance
-- ---------------------------------------------------------------------
CREATE TABLE ai_interactions (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT REFERENCES users (id),
    interaction_type VARCHAR(20) NOT NULL CHECK (interaction_type IN ('CHAT', 'POLICY_SEARCH', 'AGENT')),
    exception_id     BIGINT REFERENCES audit_exceptions (id) ON DELETE SET NULL,
    prompt           TEXT        NOT NULL,
    response         TEXT,
    provider         VARCHAR(40),
    model            VARCHAR(80),
    sources          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    tool_calls       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    grounded         BOOLEAN,
    latency_ms       BIGINT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_interactions_user ON ai_interactions (user_id, created_at DESC);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    username    VARCHAR(50),
    action      VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40),
    entity_id   VARCHAR(80),
    outcome     VARCHAR(10) NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    details     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(255)
);
CREATE INDEX idx_audit_logs_time ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_logs_user ON audit_logs (user_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_action ON audit_logs (action);

CREATE TABLE system_settings (
    setting_key   VARCHAR(80) PRIMARY KEY,
    setting_value TEXT         NOT NULL,
    description   VARCHAR(255) NOT NULL,
    updated_by    VARCHAR(50),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
