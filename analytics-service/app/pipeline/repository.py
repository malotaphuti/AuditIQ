"""PostgreSQL access for the pipeline. Bulk movement uses COPY; set-based SQL moves data between layers."""

from __future__ import annotations

import json
import math
from collections.abc import Iterable, Sequence
from datetime import datetime
from decimal import Decimal

import pandas as pd
import psycopg
from psycopg import Connection, sql

from app.pipeline import reference as ref
from app.pipeline.validation import ReferenceData

ACTIVE_RUN_INDEX = "uq_pipeline_single_active"


class RunAlreadyActiveError(RuntimeError):
    """Raised when a run is requested while another is pending or running."""


def _py(value):
    """Convert pandas/numpy scalars to plain Python values accepted by psycopg COPY."""
    if value is None:
        return None
    if isinstance(value, float) and math.isnan(value):
        return None
    if value is pd.NaT:
        return None
    if hasattr(value, "item") and not isinstance(value, str | bytes | datetime | list):
        return value.item()
    return value


def copy_rows(conn: Connection, table: str, columns: Sequence[str], rows: Iterable[Sequence]) -> int:
    schema, _, name = table.rpartition(".")
    target = sql.Identifier(schema, name) if schema else sql.Identifier(name)
    stmt = sql.SQL("COPY {} ({}) FROM STDIN").format(target, sql.SQL(", ").join(map(sql.Identifier, columns)))
    count = 0
    with conn.cursor() as cur, cur.copy(stmt) as copy:
        for row in rows:
            copy.write_row([_py(v) for v in row])
            count += 1
    return count


# ---------------------------------------------------------------------------------------------------------------------
# Run bookkeeping (each call autocommits so progress is visible while the data load is still in flight)
# ---------------------------------------------------------------------------------------------------------------------
def create_run(conn: Connection, run_ref: str, trigger_type: str, triggered_by: str, source: str,
               parameters: dict) -> int:
    try:
        row = conn.execute(
            """INSERT INTO pipeline_runs (run_ref, status, trigger_type, triggered_by, source, parameters)
               VALUES (%s, 'RUNNING', %s, %s, %s, %s) RETURNING id""",
            (run_ref, trigger_type, triggered_by, source, json.dumps(parameters)),
        ).fetchone()
    except psycopg.errors.UniqueViolation as exc:
        if ACTIVE_RUN_INDEX in str(exc):
            raise RunAlreadyActiveError("A pipeline run is already in progress") from exc
        raise
    return int(row[0])


def update_steps(conn: Connection, run_id: int, steps: list[dict]) -> None:
    conn.execute("UPDATE pipeline_runs SET steps = %s WHERE id = %s", (json.dumps(steps), run_id))


def finish_run(conn: Connection, run_id: int, status: str, steps: list[dict], duration_ms: int,
               error: str | None = None) -> None:
    conn.execute(
        """UPDATE pipeline_runs SET status = %s, steps = %s, completed_at = now(), duration_ms = %s,
                  error_message = %s WHERE id = %s""",
        (status, json.dumps(steps), duration_ms, error, run_id),
    )


def fail_interrupted_runs(conn: Connection) -> int:
    cur = conn.execute(
        """UPDATE pipeline_runs SET status = 'FAILED', completed_at = now(),
                  error_message = 'Interrupted: the analytics service restarted during the run'
           WHERE status IN ('PENDING', 'RUNNING')""")
    return cur.rowcount


def get_run_status(conn: Connection, run_id: int) -> str | None:
    row = conn.execute("SELECT status FROM pipeline_runs WHERE id = %s", (run_id,)).fetchone()
    return row[0] if row else None


# ---------------------------------------------------------------------------------------------------------------------
# Reference data & settings
# ---------------------------------------------------------------------------------------------------------------------
def fx_rates(conn: Connection) -> dict[str, Decimal]:
    rates = dict(ref.DEFAULT_FX_TO_ZAR)
    query = "SELECT setting_key, setting_value FROM system_settings WHERE setting_key LIKE 'fx.%'"
    for key, value in conn.execute(query):
        ccy = key.removeprefix("fx.").split("_")[0]
        rates[ccy] = Decimal(value)
    return rates


def int_setting(conn: Connection, key: str, default: int) -> int:
    row = conn.execute("SELECT setting_value FROM system_settings WHERE setting_key = %s", (key,)).fetchone()
    return int(row[0]) if row else default


def upsert_accounts(conn: Connection, accounts: pd.DataFrame, run_id: int) -> int:
    conn.execute("CREATE TEMP TABLE tmp_accounts (LIKE accounts INCLUDING DEFAULTS) ON COMMIT DROP")
    cols = ["account_id", "customer_id", "account_type", "status", "branch", "currency", "opened_date",
            "last_activity_date"]
    frame = accounts[cols].astype(object).where(accounts[cols].notna(), None)
    copy_rows(conn, "tmp_accounts", cols, frame.itertuples(index=False, name=None))
    cur = conn.execute(
        """INSERT INTO accounts (account_id, customer_id, account_type, status, branch, currency, opened_date,
                                 last_activity_date, pipeline_run_id)
           SELECT account_id, customer_id, account_type, status, branch, currency, opened_date, last_activity_date, %s
           FROM tmp_accounts
           ON CONFLICT (account_id) DO UPDATE SET
               customer_id = EXCLUDED.customer_id, account_type = EXCLUDED.account_type, status = EXCLUDED.status,
               branch = EXCLUDED.branch, last_activity_date = EXCLUDED.last_activity_date,
               pipeline_run_id = EXCLUDED.pipeline_run_id, updated_at = now()""",
        (run_id,))
    return cur.rowcount


def upsert_merchants(conn: Connection, merchants: pd.DataFrame, run_id: int) -> int:
    conn.execute("CREATE TEMP TABLE tmp_merchants (LIKE merchants INCLUDING DEFAULTS) ON COMMIT DROP")
    cols = ["merchant_id", "merchant_name", "merchant_category", "city", "country", "is_vendor"]
    frame = merchants[cols].astype(object).where(merchants[cols].notna(), None)
    copy_rows(conn, "tmp_merchants", cols, frame.itertuples(index=False, name=None))
    cur = conn.execute(
        """INSERT INTO merchants (merchant_id, merchant_name, merchant_category, city, country, is_vendor,
                                  pipeline_run_id)
           SELECT merchant_id, merchant_name, merchant_category, city, country, is_vendor, %s FROM tmp_merchants
           ON CONFLICT (merchant_id) DO UPDATE SET
               merchant_name = EXCLUDED.merchant_name, merchant_category = EXCLUDED.merchant_category,
               city = EXCLUDED.city, is_vendor = EXCLUDED.is_vendor, pipeline_run_id = EXCLUDED.pipeline_run_id""",
        (run_id,))
    return cur.rowcount


def reference_data(conn: Connection, fx: dict[str, Decimal]) -> ReferenceData:
    accounts = {a: (c, t) for a, c, t in conn.execute("SELECT account_id, customer_id, account_type FROM accounts")}
    merchants = dict(conn.execute("SELECT merchant_id, merchant_category FROM merchants").fetchall())
    return ReferenceData(accounts=accounts, merchants=merchants, fx_to_zar=fx)


def already_loaded(conn: Connection, transaction_ids: Sequence[str]) -> set[str]:
    ids = [i for i in set(transaction_ids) if i]
    found: set[str] = set()
    for start in range(0, len(ids), 20_000):
        chunk = ids[start:start + 20_000]
        found.update(r[0] for r in conn.execute(
            "SELECT transaction_id FROM transactions WHERE transaction_id = ANY(%s)", (chunk,)))
    return found


# ---------------------------------------------------------------------------------------------------------------------
# Layer loads
# ---------------------------------------------------------------------------------------------------------------------
def load_raw(conn: Connection, run_id: int, source_file: str, raw: pd.DataFrame) -> pd.Series:
    """Copies the feed verbatim into raw.transactions_raw and returns the generated raw ids (aligned to ``raw``)."""
    cols = list(ref.RAW_COLUMNS)
    rows = ((run_id, source_file, i + 1, *values)
            for i, values in enumerate(raw[cols].itertuples(index=False, name=None)))
    copy_rows(conn, "raw.transactions_raw", ["pipeline_run_id", "source_file", "source_row", *cols], rows)
    mapping = dict(conn.execute(
        "SELECT source_row, id FROM raw.transactions_raw WHERE pipeline_run_id = %s", (run_id,)).fetchall())
    return pd.Series([mapping[i + 1] for i in range(len(raw))], index=raw.index)


STAGING_COLUMNS = [
    "raw_id", "transaction_id", "account_id", "customer_id", "transaction_date", "transaction_amount", "currency",
    "original_amount", "original_currency", "transaction_type", "transaction_channel", "merchant_id",
    "merchant_category", "transaction_location", "account_type", "transaction_status", "approval_threshold",
    "approval_status", "validation_status", "rejection_reasons",
]


def load_staging(conn: Connection, run_id: int, staged: pd.DataFrame) -> int:
    frame = staged[STAGING_COLUMNS].astype(object).where(staged[STAGING_COLUMNS].notna(), None)
    rows = ((run_id, *values) for values in frame.itertuples(index=False, name=None))
    return copy_rows(conn, "staging.transactions_stg", ["pipeline_run_id", *STAGING_COLUMNS], rows)


def load_curated(conn: Connection, run_id: int) -> int:
    cur = conn.execute(
        """INSERT INTO transactions (transaction_id, account_id, customer_id, merchant_id, transaction_date,
                                     transaction_amount, currency, original_amount, original_currency,
                                     transaction_type, transaction_channel, merchant_category, transaction_location,
                                     account_type, transaction_status, approval_threshold, approval_status,
                                     pipeline_run_id)
           SELECT transaction_id, account_id, customer_id, merchant_id, transaction_date, transaction_amount, currency,
                  original_amount, original_currency, transaction_type, transaction_channel, merchant_category,
                  transaction_location, account_type, transaction_status, approval_threshold, approval_status,
                  pipeline_run_id
           FROM staging.transactions_stg
           WHERE pipeline_run_id = %s AND validation_status = 'VALID'
           ON CONFLICT (transaction_id) DO NOTHING""",
        (run_id,))
    return cur.rowcount


def load_labels(conn: Connection, labels: pd.DataFrame) -> int:
    """Ground truth for model evaluation – only for transactions that reached the curated layer."""
    if labels.empty:
        return 0
    conn.execute("CREATE TEMP TABLE tmp_labels (transaction_id VARCHAR(40), anomaly_type VARCHAR(40)) ON COMMIT DROP")
    copy_rows(conn, "tmp_labels", ["transaction_id", "anomaly_type"],
              labels[["transaction_id", "anomaly_type"]].itertuples(index=False, name=None))
    cur = conn.execute(
        """INSERT INTO raw.synthetic_anomaly_labels (transaction_id, anomaly_type)
           SELECT l.transaction_id, l.anomaly_type FROM tmp_labels l
           JOIN transactions t ON t.transaction_id = l.transaction_id
           ON CONFLICT (transaction_id) DO NOTHING""")
    return cur.rowcount


def insert_quality_report(conn: Connection, run_id: int, report: dict) -> None:
    conn.execute(
        """INSERT INTO data_quality_reports (pipeline_run_id, total_records, valid_records, rejected_records,
                                             duplicate_records, missing_value_total, missing_values,
                                             rejection_reasons, processing_duration_ms, status)
           VALUES (%(run_id)s, %(total)s, %(valid)s, %(rejected)s, %(duplicates)s, %(missing_total)s,
                   %(missing)s, %(reasons)s, %(duration)s, %(status)s)""",
        {"run_id": run_id, "total": report["total"], "valid": report["valid"], "rejected": report["rejected"],
         "duplicates": report["duplicates"], "missing_total": report["missing_total"],
         "missing": json.dumps(report["missing"]), "reasons": json.dumps(report["reasons"]),
         "duration": report["duration_ms"], "status": report["status"]})


def purge_raw_layer(conn: Connection, retention_days: int) -> int:
    cur = conn.execute(
        """DELETE FROM raw.transactions_raw r USING pipeline_runs p
           WHERE r.pipeline_run_id = p.id AND p.started_at < now() - make_interval(days => %s)""",
        (retention_days,))
    return cur.rowcount
