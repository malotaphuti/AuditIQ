-- Reference data (no credentials: demo users are created at start-up from environment variables)

INSERT INTO roles (name, description) VALUES
    ('ADMIN', 'Manages users, configuration and system settings'),
    ('AUDITOR', 'Views transactions, reviews anomalies and performs investigations'),
    ('AUDIT_MANAGER', 'Views dashboards, reviews investigation summaries and monitors audit activity');

INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('retention.audit_logs_days', '2555', 'Retention period for audit log entries (7 years)'),
    ('retention.ai_interactions_days', '365', 'Retention period for AI interaction logs'),
    ('retention.raw_layer_days', '90', 'Retention period for raw ingested records'),
    ('ml.priority_threshold_high', '0.70', 'Normalised anomaly score at or above which a finding is HIGH priority'),
    ('ml.priority_threshold_medium', '0.55', 'Normalised anomaly score at or above which a finding is MEDIUM priority'),
    ('ai.require_human_review', 'true', 'AI-generated investigation reports must be reviewed by a human');
