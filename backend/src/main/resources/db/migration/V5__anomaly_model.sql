-- Phase 4: Isolation Forest anomaly detection.

-- Score thresholds (Isolation Forest score in (0, 1); about 0.5 or lower is ordinary). Calibrated on the seed-42
-- synthetic dataset; administrators can change them. Scores measure unusualness – they are not fraud probabilities.
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('ml.anomaly_threshold', '0.55', 'Anomaly score at or above which a transaction is flagged (LOW priority)'),
    ('ml.exception_min_priority', 'MEDIUM',
     'Lowest model priority (LOW, MEDIUM or HIGH) that raises a potential exception for review');
UPDATE system_settings SET setting_value = '0.60',
       description = 'Anomaly score at or above which a flagged transaction is MEDIUM priority'
WHERE setting_key = 'ml.priority_threshold_medium';
UPDATE system_settings SET setting_value = '0.65',
       description = 'Anomaly score at or above which a flagged transaction is HIGH priority'
WHERE setting_key = 'ml.priority_threshold_high';

CREATE INDEX idx_pred_transaction ON anomaly_predictions (transaction_id);
CREATE INDEX idx_pred_model_anomaly ON anomaly_predictions (model_version_id) WHERE is_anomaly;
CREATE INDEX idx_exceptions_prediction ON audit_exceptions (prediction_id);
CREATE INDEX idx_ml_versions_trained ON ml_model_versions (trained_at DESC);
