-- Phase 5: analytics layer for Power BI and the in-app risk dashboard.
--
-- A star schema of views over the curated tables. Business dates are local (SAST). Views run with their owner's
-- privileges, so a reporting login needs access to this schema only – never to users, audit logs or raw data.
-- Monetary values stay NUMERIC; anomaly scores measure unusualness and are not fraud probabilities.

-- ---------------------------------------------------------------------------------------------------------------------
-- Dimensions
-- ---------------------------------------------------------------------------------------------------------------------
CREATE VIEW analytics.dim_date AS
SELECT to_char(d, 'YYYYMMDD')::int                 AS date_key,
       d::date                                     AS calendar_date,
       extract(year FROM d)::int                   AS year,
       'Q' || extract(quarter FROM d)::int         AS quarter,
       extract(month FROM d)::int                  AS month_number,
       to_char(d, 'FMMonth')                       AS month_name,
       to_char(d, 'Mon YYYY')                      AS month_label,
       to_char(d, 'YYYY-MM')                       AS year_month,
       extract(week FROM d)::int                   AS iso_week,
       extract(isodow FROM d)::int                 AS day_of_week,
       to_char(d, 'FMDay')                         AS day_name,
       extract(isodow FROM d) IN (6, 7)            AS is_weekend
FROM generate_series(DATE '2020-01-01', DATE '2030-12-31', INTERVAL '1 day') AS d;

CREATE VIEW analytics.dim_account AS
SELECT account_id, customer_id, account_type, status AS account_status, branch, opened_date
FROM accounts;

CREATE VIEW analytics.dim_merchant AS
SELECT merchant_id, merchant_name, merchant_category, city, country, is_vendor
FROM merchants;

-- Every detector that can raise a potential exception: the SQL audit rules and the anomaly model.
CREATE VIEW analytics.dim_detector AS
SELECT rule_id AS detector_key, rule_name AS detector_name, 'Audit rule' AS detector_type, risk_category,
       severity AS default_severity, enabled
FROM audit_rules
UNION ALL
SELECT 'ML', 'Anomaly model (Isolation Forest)', 'Anomaly model', 'BEHAVIOURAL_ANOMALY', NULL, true;

-- ---------------------------------------------------------------------------------------------------------------------
-- Facts
-- ---------------------------------------------------------------------------------------------------------------------
CREATE VIEW analytics.active_model_scores AS
SELECT p.transaction_id, p.anomaly_score, p.is_anomaly, p.priority, m.version AS model_version
FROM anomaly_predictions p
JOIN ml_model_versions m ON m.id = p.model_version_id AND m.is_active;

CREATE VIEW analytics.fact_transactions AS
SELECT t.transaction_id,
       to_char(t.transaction_date AT TIME ZONE 'Africa/Johannesburg', 'YYYYMMDD')::int AS date_key,
       (t.transaction_date AT TIME ZONE 'Africa/Johannesburg')::date                    AS transaction_date,
       extract(hour FROM t.transaction_date AT TIME ZONE 'Africa/Johannesburg')::int    AS local_hour,
       t.account_id, t.customer_id, t.merchant_id,
       t.transaction_amount                                    AS amount,
       t.transaction_type, t.transaction_channel,
       coalesce(t.merchant_category, 'NO_MERCHANT')            AS merchant_category,
       t.transaction_location                                  AS location,
       t.account_type, t.transaction_status, t.approval_status, t.approval_threshold,
       coalesce(x.rule_findings, 0)                            AS rule_findings,
       coalesce(x.model_findings, 0)                           AS model_findings,
       (x.transaction_id IS NOT NULL)                          AS is_flagged,
       s.anomaly_score, s.priority                             AS anomaly_priority,
       t.pipeline_run_id
FROM transactions t
LEFT JOIN (SELECT transaction_id,
                  count(*) FILTER (WHERE detection_source = 'RULE') AS rule_findings,
                  count(*) FILTER (WHERE detection_source = 'ML')   AS model_findings
           FROM audit_exceptions GROUP BY transaction_id) x ON x.transaction_id = t.transaction_id
LEFT JOIN analytics.active_model_scores s ON s.transaction_id = t.transaction_id;

CREATE VIEW analytics.fact_exceptions AS
SELECT e.id                                                    AS exception_id,
       e.exception_ref,
       coalesce(e.rule_id, 'ML')                               AS detector_key,
       e.detection_source,
       e.transaction_id, e.account_id, t.merchant_id,
       to_char(t.transaction_date AT TIME ZONE 'Africa/Johannesburg', 'YYYYMMDD')::int AS transaction_date_key,
       to_char(e.detected_at AT TIME ZONE 'Africa/Johannesburg', 'YYYYMMDD')::int     AS detected_date_key,
       (t.transaction_date AT TIME ZONE 'Africa/Johannesburg')::date                  AS transaction_date,
       e.severity,
       CASE e.severity WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END AS severity_rank,
       e.severity IN ('HIGH', 'CRITICAL')                      AS is_high_priority,
       e.risk_category,
       e.status                                                AS exception_status,
       t.transaction_amount                                    AS amount,
       t.transaction_type, t.transaction_channel,
       s.anomaly_score,
       i.id                                                    AS investigation_id,
       i.investigation_ref,
       i.status                                                AS investigation_status,
       i.outcome
FROM audit_exceptions e
JOIN transactions t ON t.transaction_id = e.transaction_id
LEFT JOIN analytics.active_model_scores s ON s.transaction_id = e.transaction_id
LEFT JOIN LATERAL (SELECT id, investigation_ref, status, outcome FROM investigations
                   WHERE exception_id = e.id ORDER BY created_at DESC, id DESC LIMIT 1) i ON true;

CREATE VIEW analytics.fact_investigations AS
SELECT i.id                                                    AS investigation_id,
       i.investigation_ref,
       i.exception_id, e.exception_ref,
       coalesce(e.rule_id, 'ML')                               AS detector_key,
       e.account_id, e.risk_category,
       i.status, i.status <> 'RESOLVED'                        AS is_open,
       i.priority, i.outcome,
       a.full_name                                             AS assigned_to,
       c.full_name                                             AS created_by,
       i.created_at,
       to_char(i.created_at AT TIME ZONE 'Africa/Johannesburg', 'YYYYMMDD')::int  AS created_date_key,
       i.resolved_at,
       to_char(i.resolved_at AT TIME ZONE 'Africa/Johannesburg', 'YYYYMMDD')::int AS resolved_date_key,
       round((extract(epoch FROM i.resolved_at - i.created_at) / 3600)::numeric, 2) AS resolution_hours,
       round((extract(epoch FROM coalesce(i.resolved_at, now()) - i.created_at) / 3600)::numeric, 2) AS age_hours,
       (SELECT count(*) FROM investigation_notes n WHERE n.investigation_id = i.id) AS notes,
       (SELECT count(*) FROM investigation_status_history h WHERE h.investigation_id = i.id) AS status_changes
FROM investigations i
JOIN audit_exceptions e ON e.id = i.exception_id
LEFT JOIN users a ON a.id = i.assigned_to
JOIN users c ON c.id = i.created_by;

CREATE VIEW analytics.fact_pipeline_runs AS
SELECT r.id AS run_id, r.run_ref, r.status, r.trigger_type, r.triggered_by, r.started_at,
       to_char(r.started_at AT TIME ZONE 'Africa/Johannesburg', 'YYYYMMDD')::int AS start_date_key,
       round(r.duration_ms / 1000.0, 1) AS duration_seconds,
       q.total_records, q.valid_records, q.rejected_records, q.duplicate_records, q.missing_value_total,
       round(100.0 * q.valid_records / nullif(q.total_records, 0), 2) AS valid_rate_percent
FROM pipeline_runs r
LEFT JOIN data_quality_reports q ON q.pipeline_run_id = r.id;

CREATE VIEW analytics.fact_model_versions AS
SELECT m.version, m.algorithm, m.trained_at, m.trained_by, m.training_rows, m.is_active,
       (m.hyperparameters ->> 'n_estimators')::int                       AS n_estimators,
       (m.metrics -> 'metrics' ->> 'precision')::numeric                AS precision,
       (m.metrics -> 'metrics' ->> 'recall')::numeric                   AS recall,
       (m.metrics -> 'metrics' ->> 'f1')::numeric                       AS f1,
       (m.metrics -> 'metrics' ->> 'rocAuc')::numeric                   AS roc_auc,
       (m.metrics -> 'metrics' ->> 'averagePrecision')::numeric         AS average_precision,
       (m.metrics ->> 'flagged')::int                                    AS flagged
FROM ml_model_versions m;

-- Accounts with potential exceptions, for "top accounts requiring review".
CREATE VIEW analytics.account_risk AS
SELECT e.account_id, a.account_type, a.status AS account_status, a.branch,
       count(*)                                                            AS exceptions,
       count(*) FILTER (WHERE e.detection_source = 'RULE')                 AS rule_findings,
       count(*) FILTER (WHERE e.detection_source = 'ML')                   AS model_findings,
       count(*) FILTER (WHERE e.severity IN ('HIGH', 'CRITICAL'))          AS high_priority,
       count(*) FILTER (WHERE e.status = 'OPEN')                           AS awaiting_review,
       count(DISTINCT coalesce(e.rule_id, 'ML'))                           AS detectors,
       count(DISTINCT e.transaction_id)                                    AS flagged_transactions,
       sum(t.transaction_amount)                                           AS flagged_amount,
       max(s.anomaly_score)                                                AS max_anomaly_score,
       count(DISTINCT i.id) FILTER (WHERE i.status <> 'RESOLVED')          AS open_investigations,
       max(e.detected_at)                                                  AS last_detected_at
FROM audit_exceptions e
JOIN accounts a ON a.account_id = e.account_id
JOIN transactions t ON t.transaction_id = e.transaction_id
LEFT JOIN analytics.active_model_scores s ON s.transaction_id = e.transaction_id
LEFT JOIN investigations i ON i.exception_id = e.id
GROUP BY e.account_id, a.account_type, a.status, a.branch;

-- ---------------------------------------------------------------------------------------------------------------------
-- Read-only reporting role (no login). A login for Power BI is granted this role at start-up when
-- BI_READER_PASSWORD is configured – credentials never live in migrations.
-- ---------------------------------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_reader') THEN
        CREATE ROLE analytics_reader NOLOGIN;
    END IF;
END
$$;
GRANT USAGE ON SCHEMA analytics TO analytics_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO analytics_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT ON TABLES TO analytics_reader;
