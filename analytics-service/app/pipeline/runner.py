"""Pipeline orchestration: extract -> raw -> validate/standardise -> staging -> curated -> quality report.

A run record is created synchronously (so callers get an id immediately and concurrent runs are refused); the work
then executes on a background thread. Each step is timed and persisted as it completes so the UI can show progress.
The data load is a single database transaction: a failure leaves no partial data and marks the run FAILED.
"""

from __future__ import annotations

import logging
import re
import secrets
import threading
import time
from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

import pandas as pd
import psycopg

from app.pipeline import reference as ref
from app.pipeline import repository as repo
from app.pipeline.generator import generate_dataset
from app.pipeline.validation import validate

log = logging.getLogger(__name__)

FOLDER_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
REJECTION_WARNING_RATE = 0.05
MIN_TRANSACTIONS, MAX_TRANSACTIONS = 1_000, 500_000


@dataclass(frozen=True)
class RunRequest:
    source: str  # GENERATED | CSV
    triggered_by: str
    trigger_type: str = "MANUAL"
    seed: int | None = None
    transaction_count: int = 110_000
    folder: str | None = None

    def validated(self) -> RunRequest:
        if self.source not in ("GENERATED", "CSV"):
            raise ValueError("source must be GENERATED or CSV")
        if self.trigger_type not in ("MANUAL", "SCHEDULED"):
            raise ValueError("trigger_type must be MANUAL or SCHEDULED")
        if self.source == "GENERATED" and not MIN_TRANSACTIONS <= self.transaction_count <= MAX_TRANSACTIONS:
            raise ValueError(f"transaction_count must be between {MIN_TRANSACTIONS} and {MAX_TRANSACTIONS}")
        if self.source == "CSV" and (not self.folder or not FOLDER_PATTERN.match(self.folder)):
            raise ValueError("folder must be 1-64 letters, digits, '-' or '_'")
        return self


@dataclass
class RunHandle:
    run_id: int
    run_ref: str
    thread: threading.Thread | None = field(default=None, repr=False)


class PipelineRunner:
    def __init__(self, connect: Callable[[], psycopg.Connection], data_dir: Path):
        self._connect = connect
        self.data_dir = data_dir

    # -- public API ----------------------------------------------------------------------------------------------
    def list_incoming(self) -> list[str]:
        incoming = self.data_dir / "incoming"
        if not incoming.is_dir():
            return []
        return sorted(p.name for p in incoming.iterdir()
                      if p.is_dir() and FOLDER_PATTERN.match(p.name) and (p / "transactions.csv").is_file())

    def start(self, request: RunRequest, background: bool = True) -> RunHandle:
        request = request.validated()
        if request.source == "CSV" and not (self.data_dir / "incoming" / request.folder / "transactions.csv").is_file():
            raise FileNotFoundError(f"incoming/{request.folder}/transactions.csv not found")
        seed = request.seed if request.seed is not None else secrets.randbelow(2**31)
        run_ref = f"RUN-{datetime.now(UTC):%Y%m%d-%H%M%S}-{secrets.token_hex(2).upper()}"
        parameters = {"source": request.source, "seed": seed if request.source == "GENERATED" else None,
                      "transactionCount": request.transaction_count if request.source == "GENERATED" else None,
                      "folder": request.folder}
        source_label = (f"synthetic generator (seed {seed})" if request.source == "GENERATED"
                        else f"incoming/{request.folder}")
        with self._connect() as conn:
            run_id = repo.create_run(conn, run_ref, request.trigger_type, request.triggered_by, source_label,
                                     {k: v for k, v in parameters.items() if v is not None})
        handle = RunHandle(run_id, run_ref)
        work = lambda: self._execute(run_id, run_ref, request, seed)  # noqa: E731
        if background:
            handle.thread = threading.Thread(target=work, name=f"pipeline-{run_ref}", daemon=True)
            handle.thread.start()
        else:
            work()
        return handle

    # -- execution -----------------------------------------------------------------------------------------------
    def _execute(self, run_id: int, run_ref: str, request: RunRequest, seed: int) -> None:
        started = time.perf_counter()
        steps: list[dict] = []

        def persist() -> None:
            try:
                with self._connect() as c:
                    repo.update_steps(c, run_id, steps)
            except Exception:  # noqa: BLE001 - progress reporting must never break the run
                log.warning("Could not persist progress for %s", run_ref, exc_info=True)

        @contextmanager
        def step(name: str):
            entry = {"name": name, "status": "RUNNING", "startedAt": datetime.now(UTC).isoformat()}
            steps.append(entry)
            persist()
            t0 = time.perf_counter()
            try:
                yield entry
                entry["status"] = "SUCCESS"
            except Exception as exc:
                entry["status"] = "FAILED"
                entry["message"] = str(exc)[:500]
                raise
            finally:
                entry["durationMs"] = int((time.perf_counter() - t0) * 1000)
                persist()

        try:
            with step("extract") as s:
                folder = self._extract(request, run_ref, seed, s)
                raw = pd.read_csv(folder / "transactions.csv", dtype=str, keep_default_na=False)
                missing_cols = [c for c in ref.RAW_COLUMNS if c not in raw.columns]
                if missing_cols:
                    raise ValueError(f"transactions.csv is missing columns: {', '.join(missing_cols)}")
                s["rows"] = len(raw)
                s.setdefault("details", {})["folder"] = folder.relative_to(self.data_dir).as_posix()

            with self._connect() as conn, conn.transaction():
                with step("load_reference_data") as s:
                    details = {}
                    if (folder / "accounts.csv").is_file():
                        details["accounts"] = repo.upsert_accounts(
                            conn, pd.read_csv(folder / "accounts.csv", dtype={"is_vendor": bool}), run_id)
                    if (folder / "merchants.csv").is_file():
                        details["merchants"] = repo.upsert_merchants(
                            conn, pd.read_csv(folder / "merchants.csv"), run_id)
                    s["details"] = details

                with step("ingest_raw") as s:
                    raw["raw_id"] = repo.load_raw(conn, run_id, f"{folder.name}/transactions.csv", raw)
                    s["rows"] = len(raw)

                with step("validate_and_standardise") as s:
                    result = validate(raw, repo.reference_data(conn, repo.fx_rates(conn)),
                                      repo.already_loaded(conn, raw["transaction_id"].str.strip().tolist()))
                    s["rows"] = result.total_records
                    s["details"] = {"valid": result.valid_records, "rejected": result.rejected_records,
                                    "duplicates": result.duplicate_records,
                                    "standardisations": result.standardisations}

                with step("load_staging") as s:
                    s["rows"] = repo.load_staging(conn, run_id, result.staged)

                with step("load_curated") as s:
                    s["rows"] = repo.load_curated(conn, run_id)
                    if (folder / "labels.csv").is_file():
                        s["details"] = {"groundTruthLabels": repo.load_labels(
                            conn, pd.read_csv(folder / "labels.csv", dtype=str))}

                rejected_rate = result.rejected_records / max(result.total_records, 1)
                status = "PARTIAL" if rejected_rate > REJECTION_WARNING_RATE else "SUCCESS"
                with step("quality_report"):
                    repo.insert_quality_report(conn, run_id, {
                        "total": result.total_records, "valid": result.valid_records,
                        "rejected": result.rejected_records, "duplicates": result.duplicate_records,
                        "missing_total": result.missing_value_total, "missing": result.missing_values,
                        "reasons": result.rejection_reasons,
                        "duration_ms": int((time.perf_counter() - started) * 1000), "status": status})

                with step("apply_retention") as s:
                    days = repo.int_setting(conn, "retention.raw_layer_days", 90)
                    s["rows"] = repo.purge_raw_layer(conn, days)
                    s["details"] = {"retentionDays": days}

            with self._connect() as conn:
                repo.finish_run(conn, run_id, status, steps, int((time.perf_counter() - started) * 1000))
            log.info("Pipeline %s finished: %s (%d valid, %d rejected, %d duplicates)", run_ref, status,
                     result.valid_records, result.rejected_records, result.duplicate_records)
        except Exception as exc:  # noqa: BLE001 - any failure must be recorded against the run
            log.exception("Pipeline %s failed", run_ref)
            try:
                with self._connect() as conn:
                    repo.finish_run(conn, run_id, "FAILED", steps, int((time.perf_counter() - started) * 1000),
                                    f"{type(exc).__name__}: {exc}"[:2000])
            except Exception:  # noqa: BLE001
                log.exception("Could not record failure for %s", run_ref)

    def _extract(self, request: RunRequest, run_ref: str, seed: int, entry: dict) -> Path:
        if request.source == "CSV":
            return self.data_dir / "incoming" / request.folder
        dataset = generate_dataset(seed=seed, n_transactions=request.transaction_count)
        folder = dataset.write_csv(self.data_dir / "generated" / run_ref)
        entry["details"] = {"generator": dataset.stats}
        return folder
