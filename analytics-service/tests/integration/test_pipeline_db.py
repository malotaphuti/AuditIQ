"""End-to-end pipeline tests against a real PostgreSQL + pgvector database.

Requires TEST_DATABASE_ADMIN_URL (see conftest.py).
"""

import pandas as pd
import psycopg
import pytest

from app.pipeline import repository as repo
from app.pipeline.generator import generate_dataset
from app.pipeline.runner import PipelineRunner, RunRequest
from tests.integration.helpers import fetch


@pytest.fixture
def runner(database_url, tmp_path):
    return PipelineRunner(lambda: psycopg.connect(database_url, autocommit=True), tmp_path)


def test_generated_run_loads_all_layers_and_reports_quality(runner, database_url):
    handle = runner.start(RunRequest(source="GENERATED", triggered_by="tester", seed=11, transaction_count=3_000),
                          background=False)
    (status, steps, error), = fetch(database_url, "SELECT status, steps, error_message FROM pipeline_runs WHERE id=%s",
                                    handle.run_id)
    assert status == "SUCCESS", error
    assert [s["name"] for s in steps] == ["extract", "load_reference_data", "ingest_raw", "validate_and_standardise",
                                          "load_staging", "load_curated", "quality_report", "apply_retention"]
    assert all(s["status"] == "SUCCESS" and "durationMs" in s for s in steps)

    (total, valid, rejected, dups, missing), = fetch(database_url, """
        SELECT total_records, valid_records, rejected_records, duplicate_records, missing_value_total
        FROM data_quality_reports WHERE pipeline_run_id = %s""", handle.run_id)
    assert total == valid + rejected + dups and rejected > 0 and dups > 0 and missing > 0

    raw_rows, = fetch(database_url, "SELECT count(*) FROM raw.transactions_raw WHERE pipeline_run_id=%s", handle.run_id)
    stg_rows, = fetch(database_url, "SELECT count(*) FROM staging.transactions_stg WHERE pipeline_run_id=%s",
                      handle.run_id)
    curated, = fetch(database_url, "SELECT count(*) FROM transactions WHERE pipeline_run_id=%s", handle.run_id)
    assert raw_rows[0] == stg_rows[0] == total
    assert curated[0] == valid

    labels, = fetch(database_url, "SELECT count(*) FROM raw.synthetic_anomaly_labels")
    assert labels[0] > 0
    # Curated amounts are exact decimals in ZAR.
    bad, = fetch(database_url, "SELECT count(*) FROM transactions WHERE currency <> 'ZAR' OR transaction_amount < 0")
    assert bad[0] == 0


def test_rerunning_the_same_seed_is_idempotent(runner, database_url):
    before, = fetch(database_url, "SELECT count(*) FROM transactions")
    handle = runner.start(RunRequest(source="GENERATED", triggered_by="tester", seed=11, transaction_count=3_000),
                          background=False)
    after, = fetch(database_url, "SELECT count(*) FROM transactions")
    assert after == before
    (valid, dups), = fetch(database_url,
                           "SELECT valid_records, duplicate_records FROM data_quality_reports WHERE pipeline_run_id=%s",
                           handle.run_id)
    assert valid == 0 and dups > 0


def test_csv_ingestion_from_incoming_folder(runner, database_url):
    generate_dataset(seed=12, n_transactions=1_500).write_csv(runner.data_dir / "incoming" / "batch_a")
    assert runner.list_incoming() == ["batch_a"]
    handle = runner.start(RunRequest(source="CSV", triggered_by="tester", folder="batch_a"), background=False)
    (status, source), = fetch(database_url, "SELECT status, source FROM pipeline_runs WHERE id=%s", handle.run_id)
    assert status == "SUCCESS" and source == "incoming/batch_a"


def test_failed_run_is_recorded_and_leaves_no_partial_data(runner, database_url):
    folder = runner.data_dir / "incoming" / "broken"
    folder.mkdir(parents=True)
    pd.DataFrame({"transaction_id": ["X1"], "account_id": ["ACC-000001"]}).to_csv(folder / "transactions.csv",
                                                                                index=False)
    before, = fetch(database_url, "SELECT count(*) FROM raw.transactions_raw")
    handle = runner.start(RunRequest(source="CSV", triggered_by="tester", folder="broken"), background=False)
    (status, error), = fetch(database_url, "SELECT status, error_message FROM pipeline_runs WHERE id=%s", handle.run_id)
    assert status == "FAILED" and "missing columns" in error
    after, = fetch(database_url, "SELECT count(*) FROM raw.transactions_raw")
    assert after == before


def test_database_failure_mid_run_rolls_back(runner, database_url, monkeypatch):
    def boom(*_args, **_kwargs):
        raise RuntimeError("simulated failure while loading curated data")

    monkeypatch.setattr(repo, "load_curated", boom)
    before, = fetch(database_url, "SELECT count(*) FROM staging.transactions_stg")
    handle = runner.start(RunRequest(source="GENERATED", triggered_by="tester", seed=13, transaction_count=1_000),
                          background=False)
    (status, steps), = fetch(database_url, "SELECT status, steps FROM pipeline_runs WHERE id=%s", handle.run_id)
    assert status == "FAILED"
    assert steps[-1]["name"] == "load_curated" and steps[-1]["status"] == "FAILED"
    after, = fetch(database_url, "SELECT count(*) FROM staging.transactions_stg")
    assert after == before


def test_only_one_run_may_be_active(database_url):
    with psycopg.connect(database_url, autocommit=True) as conn:
        run_id = repo.create_run(conn, "RUN-A", "MANUAL", "t", "s", {})
        with pytest.raises(repo.RunAlreadyActiveError):
            repo.create_run(conn, "RUN-B", "MANUAL", "t", "s", {})
        assert repo.fail_interrupted_runs(conn) == 1
        assert repo.get_run_status(conn, run_id) == "FAILED"


def test_invalid_requests_are_refused(runner):
    with pytest.raises(ValueError):
        runner.start(RunRequest(source="CSV", triggered_by="t", folder="../etc"))
    with pytest.raises(ValueError):
        runner.start(RunRequest(source="GENERATED", triggered_by="t", transaction_count=5))
    with pytest.raises(FileNotFoundError):
        runner.start(RunRequest(source="CSV", triggered_by="t", folder="does_not_exist"))
