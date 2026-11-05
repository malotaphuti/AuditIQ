"""SQL audit-rule engine.

Runs the configured rules against the curated transaction layer and records potential exceptions in
``audit_exceptions``. Findings are *potential* exceptions for human review – the engine never draws conclusions about
customers, employees or vendors.

* Every rule execution is recorded in ``rule_executions`` (thresholds used, rule version, matches, new exceptions,
  duration, status and any error), grouped by a batch reference per request.
* Each rule runs in its own transaction: a failing rule is recorded as FAILED and does not affect the others.
* Re-running is idempotent: a transaction already flagged by a rule is not flagged again, so existing exceptions and
  their investigation state are preserved.
* Only one execution batch may run at a time (PostgreSQL advisory lock).
"""

from __future__ import annotations

import json
import logging
import re
import secrets
import time
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime

import psycopg
from psycopg import Connection
from psycopg.rows import dict_row

from app.rules.definitions import RULE_QUERIES
from app.rules.thresholds import ThresholdError, validate_thresholds

log = logging.getLogger(__name__)

LOCK_KEY = 7_311_001  # arbitrary application-wide advisory lock id for rule execution
RULE_ID_PATTERN = re.compile(r"^AR-\d{3}$")
STATEMENT_TIMEOUT = "300s"


class RulesBusyError(RuntimeError):
    """Raised when another rule execution batch is in progress."""


@dataclass
class RuleResult:
    ruleId: str  # noqa: N815 - JSON field names
    ruleName: str  # noqa: N815
    executionId: int  # noqa: N815
    status: str
    matchesTotal: int = 0  # noqa: N815
    exceptionsCreated: int = 0  # noqa: N815
    durationMs: int = 0  # noqa: N815
    error: str | None = None


def _insert_sql(finding_sql: str) -> str:
    return f"""
WITH findings AS MATERIALIZED ({finding_sql}),
inserted AS (
    INSERT INTO audit_exceptions (exception_ref, detection_source, rule_id, rule_execution_id, transaction_id,
                                  account_id, severity, risk_category, reason, details)
    SELECT 'EXC-' || lpad(nextval('audit_exception_ref_seq')::text, 6, '0'), 'RULE', %(_rule_id)s,
           %(_execution_id)s, f.transaction_id, f.account_id, coalesce(f.severity, %(_severity)s),
           %(_risk_category)s, f.reason,
           f.details || jsonb_build_object('ruleVersion', %(_rule_version)s, 'thresholds', %(_thresholds)s::jsonb)
    FROM findings f
    ON CONFLICT (rule_id, transaction_id) WHERE detection_source = 'RULE' DO NOTHING
    RETURNING 1
)
SELECT (SELECT count(*) FROM findings) AS matches, (SELECT count(*) FROM inserted) AS created
"""


class RuleEngine:
    def __init__(self, connect: Callable[[], psycopg.Connection]):
        self._connect = connect

    def list_rules(self) -> list[dict]:
        with self._connect() as conn:
            return conn.cursor(row_factory=dict_row).execute(
                "SELECT rule_id, rule_name, enabled FROM audit_rules ORDER BY rule_id").fetchall()

    def execute(self, executed_by: str, rule_ids: list[str] | None = None) -> dict:
        if not executed_by or len(executed_by) > 50:
            raise ValueError("executed_by must be 1-50 characters")
        if rule_ids is not None:
            if not rule_ids:
                raise ValueError("rule_ids must not be empty when provided")
            if bad := [r for r in rule_ids if not RULE_ID_PATTERN.match(r)]:
                raise ValueError(f"invalid rule id(s): {', '.join(bad)}")

        batch_ref = f"RULES-{datetime.now(UTC):%Y%m%d-%H%M%S}-{secrets.token_hex(2).upper()}"
        started = time.perf_counter()
        with self._connect() as conn:
            if not conn.execute("SELECT pg_try_advisory_lock(%s)", (LOCK_KEY,)).fetchone()[0]:
                raise RulesBusyError("Audit rules are already being executed")
            try:
                conn.execute(f"SET statement_timeout = '{STATEMENT_TIMEOUT}'")
                rules = self._load_rules(conn, rule_ids)
                results = [self._run_rule(conn, rule, batch_ref, executed_by) for rule in rules]
            finally:
                conn.execute("SELECT pg_advisory_unlock(%s)", (LOCK_KEY,))
        return {
            "batchRef": batch_ref,
            "executedBy": executed_by,
            "durationMs": int((time.perf_counter() - started) * 1000),
            "results": [asdict(r) for r in results],
        }

    # -----------------------------------------------------------------------------------------------------------
    @staticmethod
    def _load_rules(conn: Connection, rule_ids: list[str] | None) -> list[dict]:
        cur = conn.cursor(row_factory=dict_row)
        rows = cur.execute(
            """SELECT rule_id, rule_name, severity, risk_category, thresholds, threshold_schema, enabled, version
               FROM audit_rules ORDER BY rule_id""").fetchall()
        by_id = {r["rule_id"]: r for r in rows}
        if rule_ids is None:
            selected = [r for r in rows if r["enabled"]]
        else:
            if missing := [r for r in rule_ids if r not in by_id]:
                raise ValueError(f"unknown rule(s): {', '.join(missing)}")
            if disabled := [r for r in rule_ids if not by_id[r]["enabled"]]:
                raise ValueError(f"rule(s) disabled: {', '.join(disabled)}")
            selected = [by_id[r] for r in dict.fromkeys(rule_ids)]
        if not selected:
            raise ValueError("no enabled rules to execute")
        if unsupported := [r["rule_id"] for r in selected if r["rule_id"] not in RULE_QUERIES]:
            raise ValueError(f"no detection logic implemented for: {', '.join(unsupported)}")
        return selected

    def _run_rule(self, conn: Connection, rule: dict, batch_ref: str, executed_by: str) -> RuleResult:
        rule_id = rule["rule_id"]
        execution_id = conn.execute(
            """INSERT INTO rule_executions (rule_id, thresholds_used, status, executed_by, batch_ref, rule_version)
               VALUES (%s, %s, 'RUNNING', %s, %s, %s) RETURNING id""",
            (rule_id, json.dumps(rule["thresholds"]), executed_by, batch_ref, rule["version"])).fetchone()[0]
        result = RuleResult(rule_id, rule["rule_name"], execution_id, "RUNNING")
        started = time.perf_counter()
        try:
            thresholds = validate_thresholds(rule["thresholds"], rule["threshold_schema"])
            params = {**thresholds, "_rule_id": rule_id, "_execution_id": execution_id,
                      "_severity": rule["severity"], "_risk_category": rule["risk_category"],
                      "_rule_version": rule["version"], "_thresholds": json.dumps(thresholds)}
            with conn.transaction():
                matches, created = conn.execute(_insert_sql(RULE_QUERIES[rule_id].sql), params).fetchone()
                result.durationMs = int((time.perf_counter() - started) * 1000)
                conn.execute(
                    """UPDATE rule_executions SET status = 'SUCCESS', matches_total = %s, exceptions_found = %s,
                              completed_at = now(), duration_ms = %s WHERE id = %s""",
                    (matches, created, result.durationMs, execution_id))
                conn.execute("UPDATE audit_rules SET last_executed_at = now() WHERE rule_id = %s", (rule_id,))
            result.status, result.matchesTotal, result.exceptionsCreated = "SUCCESS", int(matches), int(created)
            log.info("Rule %s: %d matches, %d new exceptions in %d ms", rule_id, matches, created, result.durationMs)
        except (ThresholdError, psycopg.Error) as exc:
            result.durationMs = int((time.perf_counter() - started) * 1000)
            result.status = "FAILED"
            result.error = f"Invalid configuration: {exc}" if isinstance(exc, ThresholdError) else \
                f"Rule query failed: {type(exc).__name__}"
            log.exception("Rule %s failed", rule_id)
            conn.execute(
                """UPDATE rule_executions SET status = 'FAILED', completed_at = now(), duration_ms = %s,
                          error_message = %s WHERE id = %s""",
                (result.durationMs, f"{result.error}: {exc}"[:2000], execution_id))
        return result
