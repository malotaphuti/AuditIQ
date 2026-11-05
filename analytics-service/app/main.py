"""AuditIQ analytics & AI service.

Hosts the data pipeline, SQL audit-rule engine, ML anomaly detection, RAG assistant and investigation agent.
It is an internal service: every endpoint except /health requires the shared service key.
"""

import logging
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import APIRouter, Depends, FastAPI, HTTPException, status
from pydantic import BaseModel, Field

from app import db
from app.config import get_settings
from app.db import database_is_up
from app.pipeline import repository
from app.pipeline.runner import MAX_TRANSACTIONS, MIN_TRANSACTIONS, PipelineRunner, RunRequest
from app.rules.engine import RuleEngine, RulesBusyError
from app.scheduler import IntervalScheduler
from app.security import require_service_key

settings = get_settings()
logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger(__name__)

runner = PipelineRunner(db.connect, settings.data_dir)
rule_engine = RuleEngine(db.connect)


@asynccontextmanager
async def lifespan(_: FastAPI):
    try:
        with db.connect() as conn:
            if (n := repository.fail_interrupted_runs(conn)) > 0:
                log.warning("Marked %d interrupted pipeline run(s) as FAILED", n)
    except Exception:  # noqa: BLE001 - the service must still start when the database is briefly unavailable
        log.warning("Could not reconcile interrupted runs at start-up", exc_info=True)
    scheduler = IntervalScheduler(runner, settings.schedule_minutes, settings.scheduled_transaction_count)
    scheduler.start()
    yield
    scheduler.stop()


app = FastAPI(title="AuditIQ Analytics Service", version=settings.version, lifespan=lifespan)
internal = APIRouter(prefix="/internal", dependencies=[Depends(require_service_key)])


@app.get("/health")
def health() -> dict:
    db_up = database_is_up()
    return {
        "status": "UP" if db_up else "DEGRADED",
        "database": "UP" if db_up else "DOWN",
        "service": settings.app_name,
        "version": settings.version,
    }


@internal.get("/ping")
def ping() -> dict:
    """Authenticated connectivity check used by the backend."""
    return {"status": "ok"}


class PipelineRunBody(BaseModel):
    source: Literal["GENERATED", "CSV"] = "GENERATED"
    triggered_by: str = Field(alias="triggeredBy", min_length=1, max_length=50)
    seed: int | None = Field(default=None, ge=0, le=2**31 - 1)
    transaction_count: int = Field(default=110_000, alias="transactionCount", ge=MIN_TRANSACTIONS,
                                   le=MAX_TRANSACTIONS)
    folder: str | None = Field(default=None, max_length=64)


@internal.post("/pipeline/runs", status_code=status.HTTP_202_ACCEPTED)
def start_pipeline(body: PipelineRunBody) -> dict:
    try:
        handle = runner.start(RunRequest(source=body.source, triggered_by=body.triggered_by, seed=body.seed,
                                         transaction_count=body.transaction_count, folder=body.folder))
    except repository.RunAlreadyActiveError as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc
    except (ValueError, FileNotFoundError) as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
    return {"runId": handle.run_id, "runRef": handle.run_ref, "status": "RUNNING"}


@internal.get("/pipeline/sources")
def pipeline_sources() -> dict:
    """CSV folders available under <data>/incoming for ingestion."""
    return {"folders": runner.list_incoming()}


class RuleExecutionBody(BaseModel):
    executed_by: str = Field(alias="executedBy", min_length=1, max_length=50)
    rule_ids: list[str] | None = Field(default=None, alias="ruleIds", max_length=50)


@internal.post("/rules/execute")
def execute_rules(body: RuleExecutionBody) -> dict:
    """Runs the selected (or all enabled) audit rules synchronously and returns per-rule results."""
    try:
        return rule_engine.execute(body.executed_by, body.rule_ids)
    except RulesBusyError as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc


app.include_router(internal)
