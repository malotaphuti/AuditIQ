"""AuditIQ analytics & AI service.

Hosts the data pipeline, SQL audit-rule engine, ML anomaly detection, RAG assistant and investigation agent.
It is an internal service: every endpoint except /health requires the shared service key.
"""

import logging

from fastapi import APIRouter, Depends, FastAPI

from app.config import get_settings
from app.db import database_is_up
from app.security import require_service_key

settings = get_settings()
logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s - %(message)s")

app = FastAPI(title="AuditIQ Analytics Service", version=settings.version)

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


app.include_router(internal)
