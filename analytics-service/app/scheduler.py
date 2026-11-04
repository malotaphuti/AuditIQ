"""Lightweight interval scheduler for unattended pipeline runs (an Airflow DAG would replace this in production)."""

import logging
import threading

from app.pipeline.repository import RunAlreadyActiveError
from app.pipeline.runner import PipelineRunner, RunRequest

log = logging.getLogger(__name__)


class IntervalScheduler:
    def __init__(self, runner: PipelineRunner, minutes: int, transaction_count: int):
        self._runner = runner
        self._interval = minutes * 60
        self._transaction_count = transaction_count
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._interval <= 0:
            log.info("Pipeline scheduler disabled")
            return
        self._thread = threading.Thread(target=self._loop, name="pipeline-scheduler", daemon=True)
        self._thread.start()
        log.info("Pipeline scheduler enabled: every %d minutes", self._interval // 60)

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                handle = self._runner.start(RunRequest(source="GENERATED", triggered_by="scheduler",
                                                       trigger_type="SCHEDULED",
                                                       transaction_count=self._transaction_count))
                log.info("Scheduled pipeline run %s started", handle.run_ref)
            except RunAlreadyActiveError:
                log.info("Scheduled run skipped: another run is in progress")
            except Exception:  # noqa: BLE001 - the scheduler must survive individual failures
                log.exception("Scheduled pipeline run could not be started")
