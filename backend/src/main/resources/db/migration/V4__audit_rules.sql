-- Phase 3: SQL audit rules, rule execution tracking and exception references.

-- Each rule describes its configurable thresholds (type, bounds, allowed values, label) so the API, the rule engine
-- and the UI validate and render them from one definition.
ALTER TABLE audit_rules ADD COLUMN threshold_schema JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE audit_rules ADD COLUMN updated_by VARCHAR(50);

-- One "Run rules" request = one batch; each rule in it gets its own execution row.
ALTER TABLE rule_executions
    ADD COLUMN batch_ref     VARCHAR(40),
    ADD COLUMN rule_version  INT,
    ADD COLUMN matches_total INT,
    ADD COLUMN duration_ms   BIGINT;
CREATE INDEX idx_rule_exec_batch ON rule_executions (batch_ref);
CREATE INDEX idx_rule_exec_started ON rule_executions (started_at DESC);

CREATE SEQUENCE audit_exception_ref_seq;
CREATE SEQUENCE investigation_ref_seq;

CREATE INDEX idx_exceptions_rule ON audit_exceptions (rule_id);
CREATE INDEX idx_exceptions_transaction ON audit_exceptions (transaction_id);
CREATE INDEX idx_exceptions_detected ON audit_exceptions (detected_at DESC, id DESC);
CREATE INDEX idx_investigations_exception ON investigations (exception_id);
CREATE INDEX idx_investigations_updated ON investigations (updated_at DESC);

INSERT INTO audit_rules (rule_id, rule_name, description, detection_logic, risk_category, severity, thresholds,
                         threshold_schema)
VALUES
('AR-001', 'Duplicate payments',
 'Payments on the same account for the same amount on the same business day. A repeated payment may indicate a '
     || 'processing error, a re-submitted instruction or a weakness in duplicate-payment controls.',
 'Group qualifying transactions by account, amount and local (SAST) business date. Every transaction after the '
     || 'earliest in a group of two or more is a potential duplicate of that earliest transaction. Only the listed '
     || 'transaction types and statuses at or above the minimum amount are considered. Severity rises to HIGH at or '
     || 'above the high-value amount.',
 'PAYMENT_INTEGRITY', 'MEDIUM',
 '{"min_amount": 100, "high_value_amount": 25000, "match_merchant": false,
   "transaction_types": ["PURCHASE", "TRANSFER", "VENDOR_PAYMENT", "DEBIT_ORDER"],
   "statuses": ["COMPLETED", "PENDING"]}',
 '{"min_amount": {"type": "number", "min": 0, "max": 10000000, "label": "Minimum amount (ZAR)"},
   "high_value_amount": {"type": "number", "min": 0, "max": 100000000, "label": "High-severity amount (ZAR)"},
   "match_merchant": {"type": "boolean", "label": "Also require the same merchant"},
   "transaction_types": {"type": "list", "label": "Transaction types",
                         "allowed": ["PURCHASE", "TRANSFER", "WITHDRAWAL", "DEPOSIT", "VENDOR_PAYMENT", "DEBIT_ORDER"]},
   "statuses": {"type": "list", "label": "Transaction statuses",
                "allowed": ["COMPLETED", "PENDING", "FAILED", "REVERSED"]}}'),

('AR-002', 'Unusually large transactions',
 'Transactions far above the approval threshold for the account type, and transactions at or above the threshold '
     || 'that were processed without recorded approval.',
 'Flag a transaction when its amount is at least the threshold multiple of the approval threshold recorded on the '
     || 'transaction (HIGH), or when it is at or above the approval threshold and its approval status is not '
     || 'APPROVED (CRITICAL – possible approval bypass).',
 'APPROVAL_CONTROL', 'HIGH',
 '{"threshold_multiple": 1.5, "flag_unapproved": true, "statuses": ["COMPLETED", "PENDING"]}',
 '{"threshold_multiple": {"type": "number", "min": 1, "max": 100, "label": "Multiple of approval threshold"},
   "flag_unapproved": {"type": "boolean", "label": "Flag unapproved amounts at or above the threshold"},
   "statuses": {"type": "list", "label": "Transaction statuses",
                "allowed": ["COMPLETED", "PENDING", "FAILED", "REVERSED"]}}'),

('AR-003', 'Transaction splitting',
 'Several payments from one account, each just below the approval threshold, made within a short period and '
     || 'together exceeding it. This pattern can indicate an attempt to avoid approval requirements.',
 'Consider debit transactions whose amount is below the approval threshold but at least the near-threshold ratio '
     || 'of it. Flag every such transaction that has at least the minimum number of near-threshold transactions on '
     || 'the same account within the time window either side of it, where their total exceeds the threshold.',
 'APPROVAL_CONTROL', 'HIGH',
 '{"window_hours": 24, "min_transactions": 2, "near_threshold_ratio": 0.6,
   "transaction_types": ["PURCHASE", "TRANSFER", "WITHDRAWAL", "VENDOR_PAYMENT"],
   "statuses": ["COMPLETED", "PENDING"]}',
 '{"window_hours": {"type": "integer", "min": 1, "max": 720, "label": "Time window (hours)"},
   "min_transactions": {"type": "integer", "min": 2, "max": 50, "label": "Minimum transactions in window"},
   "near_threshold_ratio": {"type": "number", "min": 0.1, "max": 0.99,
                            "label": "Near-threshold ratio (share of approval threshold)"},
   "transaction_types": {"type": "list", "label": "Transaction types",
                         "allowed": ["PURCHASE", "TRANSFER", "WITHDRAWAL", "DEPOSIT", "VENDOR_PAYMENT", "DEBIT_ORDER"]},
   "statuses": {"type": "list", "label": "Transaction statuses",
                "allowed": ["COMPLETED", "PENDING", "FAILED", "REVERSED"]}}'),

('AR-004', 'High-frequency transactions',
 'An unusually large number of transactions on one account within a short period, including failed attempts. '
     || 'Bursts of activity can indicate compromised credentials, card testing or automated misuse.',
 'For each transaction count the transactions on the same account in the preceding time window. Flag every '
     || 'transaction belonging to a window that reaches the minimum count. Severity rises to HIGH when the peak '
     || 'count is at least twice the minimum.',
 'TRANSACTION_MONITORING', 'MEDIUM',
 '{"window_minutes": 60, "min_transactions": 10, "statuses": ["COMPLETED", "PENDING", "FAILED", "REVERSED"]}',
 '{"window_minutes": {"type": "integer", "min": 1, "max": 1440, "label": "Time window (minutes)"},
   "min_transactions": {"type": "integer", "min": 2, "max": 500, "label": "Minimum transactions in window"},
   "statuses": {"type": "list", "label": "Transaction statuses",
                "allowed": ["COMPLETED", "PENDING", "FAILED", "REVERSED"]}}'),

('AR-005', 'Dormant account activity',
 'Activity on an account after a long period of inactivity, or on an account recorded as dormant. Unexpected '
     || 'reactivation is a common indicator of unauthorised account access.',
 'Prior activity is the previous transaction on the account, or the last activity date in the account master when '
     || 'there is none. A transaction whose prior activity is at least the dormancy period earlier is a '
     || 'reactivation; transactions within the review period after a reactivation are also flagged. Optionally, '
     || 'every transaction on an account whose master status is DORMANT is flagged.',
 'ACCOUNT_CONTROL', 'HIGH',
 '{"dormancy_days": 365, "review_days": 90, "include_dormant_status": true, "statuses": ["COMPLETED", "PENDING"]}',
 '{"dormancy_days": {"type": "integer", "min": 30, "max": 3650, "label": "Dormancy period (days)"},
   "review_days": {"type": "integer", "min": 0, "max": 365, "label": "Review period after reactivation (days)"},
   "include_dormant_status": {"type": "boolean", "label": "Flag accounts recorded as DORMANT"},
   "statuses": {"type": "list", "label": "Transaction statuses",
                "allowed": ["COMPLETED", "PENDING", "FAILED", "REVERSED"]}}'),

('AR-006', 'Unusual vendor payments',
 'Vendor payments that depart from the established pattern: amounts well above what the vendor is normally paid, '
     || 'a sudden increase in payment frequency to a vendor, or large payments to a newly paid vendor outside '
     || 'business hours.',
 'Only vendor payments to merchants flagged as vendors are considered. A payment is flagged when (a) its amount is '
     || 'at least the amount multiple of the vendor''s median payment across all accounts; (b) the account made at '
     || 'least the minimum number of payments to the vendor within the frequency window and that count is at least '
     || 'the frequency multiple of the account''s usual rate for the vendor; or (c) it is at or above the new-vendor '
     || 'amount, made between the off-hours start and end (SAST) and within the new-vendor period of the first '
     || 'payment from the account to that vendor. Severity rises to HIGH when more than one condition applies.',
 'VENDOR_MANAGEMENT', 'MEDIUM',
 '{"amount_multiple": 3.0, "frequency_window_days": 14, "frequency_min_payments": 4, "frequency_multiple": 3.0,
   "new_vendor_days": 30, "new_vendor_min_amount": 50000, "off_hours_start": 0, "off_hours_end": 5,
   "statuses": ["COMPLETED", "PENDING"]}',
 '{"amount_multiple": {"type": "number", "min": 1, "max": 100, "label": "Multiple of vendor median amount"},
   "frequency_window_days": {"type": "integer", "min": 1, "max": 90, "label": "Frequency window (days)"},
   "frequency_min_payments": {"type": "integer", "min": 2, "max": 100, "label": "Minimum payments in window"},
   "frequency_multiple": {"type": "number", "min": 1, "max": 100, "label": "Multiple of usual payment rate"},
   "new_vendor_days": {"type": "integer", "min": 1, "max": 365, "label": "New-vendor period (days)"},
   "new_vendor_min_amount": {"type": "number", "min": 0, "max": 100000000, "label": "New-vendor amount (ZAR)"},
   "off_hours_start": {"type": "integer", "min": 0, "max": 23, "label": "Off-hours start (hour, SAST)"},
   "off_hours_end": {"type": "integer", "min": 1, "max": 24, "label": "Off-hours end (hour, SAST)"},
   "statuses": {"type": "list", "label": "Transaction statuses",
                "allowed": ["COMPLETED", "PENDING", "FAILED", "REVERSED"]}}');
