-- Phase 2: pipeline execution detail and currency standardisation lineage.

-- Per-step execution log for each run: [{"name","status","startedAt","durationMs","rows","message"}]
ALTER TABLE pipeline_runs ADD COLUMN steps JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Amounts are standardised to ZAR; the original values are retained for traceability.
ALTER TABLE staging.transactions_stg
    ADD COLUMN original_amount   NUMERIC(18, 2),
    ADD COLUMN original_currency VARCHAR(3);

ALTER TABLE transactions
    ADD COLUMN original_amount   NUMERIC(18, 2),
    ADD COLUMN original_currency VARCHAR(3);

-- Reference data loaded alongside transactions is tracked to the run that last touched it.
ALTER TABLE accounts ADD COLUMN pipeline_run_id BIGINT REFERENCES pipeline_runs (id) ON DELETE SET NULL;
ALTER TABLE merchants ADD COLUMN pipeline_run_id BIGINT REFERENCES pipeline_runs (id) ON DELETE SET NULL;

-- Only one pipeline run may be active at a time.
CREATE UNIQUE INDEX uq_pipeline_single_active ON pipeline_runs ((true)) WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_tx_category ON transactions (merchant_category);
CREATE INDEX idx_tx_channel ON transactions (transaction_channel);
CREATE INDEX idx_tx_pipeline_run ON transactions (pipeline_run_id);
CREATE INDEX idx_stg_tx_txid ON staging.transactions_stg (transaction_id);

INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('fx.USD_ZAR', '18.20', 'Reference rate used to standardise USD amounts to ZAR'),
    ('fx.EUR_ZAR', '19.70', 'Reference rate used to standardise EUR amounts to ZAR'),
    ('fx.GBP_ZAR', '23.10', 'Reference rate used to standardise GBP amounts to ZAR');
