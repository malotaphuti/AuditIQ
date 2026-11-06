"""Isolation Forest anomaly detection: training, scoring, potential-exception creation and evaluation.

* Unsupervised: the model never sees the synthetic ground-truth labels. Labels are used only by ``evaluate`` to
  measure the model, and the measurements are reported separately.
* The anomaly score is the Isolation Forest score ``s(x) = 2^(-E[h(x)] / c(n))`` in (0, 1): about 0.5 or lower is
  ordinary, and values approaching 1 are increasingly easy to isolate. It measures how unusual a transaction is. It is
  **not** a probability of fraud.
* Priority bands (LOW / MEDIUM / HIGH) come from configurable score thresholds in ``system_settings``.
* Each training run creates a new model version (hyperparameters, features, thresholds, training rows, metrics,
  artifact) and makes it the active model. Scores are stored per model version in ``anomaly_predictions``.
"""

from __future__ import annotations

import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import psycopg
import sklearn
from psycopg import Connection
from sklearn.ensemble import IsolationForest
from sklearn.metrics import average_precision_score, confusion_matrix, roc_auc_score

from app.ml.features import FEATURE_NAMES, FEATURES, ReferenceStats, build_features, explain, load_context
from app.pipeline.repository import copy_rows

log = logging.getLogger(__name__)

MODEL_NAME = "isolation_forest"
LOCK_KEY = 7_311_002
RISK_CATEGORY = "BEHAVIOURAL_ANOMALY"
DEFAULT_THRESHOLDS = {"anomaly": 0.55, "medium": 0.60, "high": 0.65}
PRIORITIES = ("LOW", "MEDIUM", "HIGH")
DEFAULT_EXCEPTION_PRIORITY = "MEDIUM"
SETTING_KEYS = {"anomaly": "ml.anomaly_threshold", "medium": "ml.priority_threshold_medium",
                "high": "ml.priority_threshold_high"}
MIN_TRAINING_ROWS = 1_000
SCORE_NOTE = ("The anomaly score measures how unusual the transaction is compared with the data; "
              "it is not a probability of fraud.")


class ModelBusyError(RuntimeError):
    """Raised when another training or detection run is in progress."""


class NoModelError(RuntimeError):
    """Raised when detection or evaluation is requested before any model has been trained."""


@dataclass(frozen=True)
class TrainingParams:
    n_estimators: int = 200
    max_samples: int = 256
    seed: int = 42

    def validated(self) -> TrainingParams:
        if not 50 <= self.n_estimators <= 1_000:
            raise ValueError("n_estimators must be between 50 and 1000")
        if not 64 <= self.max_samples <= 4_096:
            raise ValueError("max_samples must be between 64 and 4096")
        if not 0 <= self.seed <= 2**31 - 1:
            raise ValueError("seed must be a non-negative 32-bit integer")
        return self


def read_thresholds(conn: Connection) -> dict[str, float]:
    values = dict(DEFAULT_THRESHOLDS)
    rows = conn.execute("SELECT setting_key, setting_value FROM system_settings WHERE setting_key = ANY(%s)",
                        (list(SETTING_KEYS.values()),)).fetchall()
    by_key = dict(rows)
    for name, key in SETTING_KEYS.items():
        if key in by_key:
            values[name] = float(by_key[key])
    if not 0 < values["anomaly"] <= values["medium"] <= values["high"] < 1:
        raise ValueError("ML thresholds must satisfy 0 < anomaly <= medium <= high < 1")
    return values


def read_exception_priority(conn: Connection) -> str:
    row = conn.execute("SELECT setting_value FROM system_settings WHERE setting_key = 'ml.exception_min_priority'"
                       ).fetchone()
    value = (row[0] if row else DEFAULT_EXCEPTION_PRIORITY).strip().upper()
    if value not in PRIORITIES:
        raise ValueError("ml.exception_min_priority must be LOW, MEDIUM or HIGH")
    return value


def priority_for(score: float, thresholds: dict[str, float]) -> str | None:
    if score >= thresholds["high"]:
        return "HIGH"
    if score >= thresholds["medium"]:
        return "MEDIUM"
    if score >= thresholds["anomaly"]:
        return "LOW"
    return None


class AnomalyModelService:
    def __init__(self, connect: Callable[[], psycopg.Connection], model_dir: Path):
        self._connect = connect
        self.model_dir = model_dir

    # -- training --------------------------------------------------------------------------------------------------
    def train(self, trained_by: str, params: TrainingParams | None = None) -> dict:
        params = (params or TrainingParams()).validated()
        _check_actor(trained_by)
        with self._connect() as conn, _locked(conn):
            started = time.perf_counter()
            thresholds = read_thresholds(conn)
            ctx = load_context(conn)
            if len(ctx) < MIN_TRAINING_ROWS:
                raise ValueError(f"At least {MIN_TRAINING_ROWS} curated transactions are needed to train; "
                                 f"found {len(ctx)}. Run the data pipeline first.")
            features = build_features(ctx)
            model = IsolationForest(n_estimators=params.n_estimators, max_samples=params.max_samples,
                                    random_state=params.seed, n_jobs=-1).fit(features.to_numpy())
            stats = ReferenceStats.fit(features)
            scores = -model.score_samples(features.to_numpy())

            version = f"IF-{datetime.now(UTC):%Y%m%d-%H%M%S}"
            self.model_dir.mkdir(parents=True, exist_ok=True)
            artifact = self.model_dir / f"{version}.joblib"
            joblib.dump({"model": model, "features": FEATURE_NAMES, "stats": stats, "version": version}, artifact)

            hyper = {"n_estimators": params.n_estimators, "max_samples": params.max_samples,
                     "random_state": params.seed, "contamination": "auto",
                     "scikit_learn": sklearn.__version__}
            with conn.transaction():
                version_id = conn.execute(
                    """INSERT INTO ml_model_versions (model_name, version, algorithm, hyperparameters, feature_list,
                                                      priority_thresholds, training_rows, artifact_path, is_active,
                                                      trained_by)
                       VALUES (%s, %s, 'IsolationForest', %s, %s, %s, %s, %s, false, %s) RETURNING id""",
                    (MODEL_NAME, version, json.dumps(hyper),
                     json.dumps([{"name": f.name, "label": f.label} for f in FEATURES]),
                     json.dumps(thresholds), len(ctx), str(artifact), trained_by)).fetchone()[0]
                self._store_predictions(conn, version_id, ctx, features, scores, stats, thresholds)
                conn.execute("UPDATE ml_model_versions SET is_active = false WHERE model_name = %s AND is_active",
                             (MODEL_NAME,))
                conn.execute("UPDATE ml_model_versions SET is_active = true WHERE id = %s", (version_id,))
            metrics = evaluate(conn, version_id)
            metrics["trainingDurationMs"] = int((time.perf_counter() - started) * 1000)
            conn.execute("UPDATE ml_model_versions SET metrics = %s WHERE id = %s",
                         (json.dumps(_snapshot(metrics)), version_id))
            log.info("Trained %s on %d transactions in %d ms", version, len(ctx), metrics["trainingDurationMs"])
            return {"modelVersionId": version_id, "version": version, "trainingRows": len(ctx),
                    "durationMs": metrics["trainingDurationMs"], "thresholds": thresholds,
                    "metrics": _snapshot(metrics)}

    # -- detection -------------------------------------------------------------------------------------------------
    def detect(self, executed_by: str) -> dict:
        """Scores transactions not yet scored by the active model and records potential exceptions."""
        _check_actor(executed_by)
        with self._connect() as conn, _locked(conn):
            started = time.perf_counter()
            active = conn.execute(
                "SELECT id, version, artifact_path FROM ml_model_versions WHERE model_name = %s AND is_active",
                (MODEL_NAME,)).fetchone()
            if active is None:
                raise NoModelError("No anomaly model has been trained yet. Train a model first.")
            version_id, version, artifact_path = active
            thresholds = read_thresholds(conn)
            missing = {r[0] for r in conn.execute(
                """SELECT t.transaction_id FROM transactions t
                   WHERE NOT EXISTS (SELECT 1 FROM anomaly_predictions p
                                     WHERE p.transaction_id = t.transaction_id AND p.model_version_id = %s)""",
                (version_id,))}
            scored = 0
            with conn.transaction():
                if missing:
                    bundle = _load_artifact(artifact_path)
                    ctx = load_context(conn)  # full context: account history includes earlier data
                    ctx = ctx[ctx.transaction_id.isin(missing)]
                    features = build_features(ctx)
                    scores = -bundle["model"].score_samples(features.to_numpy())
                    self._store_predictions(conn, version_id, ctx, features, scores, bundle["stats"], thresholds)
                    scored = len(ctx)
                # Re-apply the current thresholds to every prediction of the active model.
                conn.execute(
                    """UPDATE anomaly_predictions SET
                           is_anomaly = anomaly_score >= %(a)s,
                           priority = CASE WHEN anomaly_score >= %(h)s THEN 'HIGH'
                                           WHEN anomaly_score >= %(m)s THEN 'MEDIUM'
                                           WHEN anomaly_score >= %(a)s THEN 'LOW' END
                       WHERE model_version_id = %(v)s""",
                    {"a": thresholds["anomaly"], "m": thresholds["medium"], "h": thresholds["high"],
                     "v": version_id})
                min_priority = read_exception_priority(conn)
                created = self._create_exceptions(conn, version_id, version, thresholds, min_priority)
            counts = dict(conn.execute(
                """SELECT priority, count(*) FROM anomaly_predictions
                   WHERE model_version_id = %s AND is_anomaly GROUP BY priority""", (version_id,)).fetchall())
            return {"modelVersionId": version_id, "version": version, "newlyScored": scored,
                    "anomalies": sum(counts.values()), "byPriority": {p: counts.get(p, 0) for p in
                                                                     ("HIGH", "MEDIUM", "LOW")},
                    "exceptionsCreated": created, "exceptionMinPriority": min_priority, "thresholds": thresholds,
                    "durationMs": int((time.perf_counter() - started) * 1000)}

    def performance(self, version_id: int | None = None) -> dict:
        with self._connect() as conn:
            if version_id is None:
                row = conn.execute("SELECT id FROM ml_model_versions WHERE model_name = %s AND is_active",
                                   (MODEL_NAME,)).fetchone()
                if row is None:
                    raise NoModelError("No anomaly model has been trained yet.")
                version_id = row[0]
            elif conn.execute("SELECT 1 FROM ml_model_versions WHERE id = %s", (version_id,)).fetchone() is None:
                raise NoModelError(f"Model version {version_id} does not exist.")
            return evaluate(conn, version_id)

    # -- persistence -----------------------------------------------------------------------------------------------
    @staticmethod
    def _store_predictions(conn: Connection, version_id: int, ctx: pd.DataFrame, features: pd.DataFrame,
                           scores: np.ndarray, stats: ReferenceStats, thresholds: dict[str, float]) -> None:
        flagged = scores >= thresholds["anomaly"]
        explanations: list[str] = ["{}"] * len(ctx)
        if flagged.any():
            idx = np.flatnonzero(flagged)
            factors = explain(features.iloc[idx], ctx.iloc[idx], stats, at_least_one=True)
            for i, f in zip(idx, factors, strict=True):
                explanations[i] = json.dumps({"method": "Robust z-score of each feature against the training "
                                                        "population; the largest deviations are listed.",
                                              "factors": f})
        rows = ((tx, version_id, round(float(s), 6), bool(s >= thresholds["anomaly"]),
                 priority_for(float(s), thresholds), e)
                for tx, s, e in zip(ctx.transaction_id, scores, explanations, strict=True))
        copy_rows(conn, "anomaly_predictions",
                  ["transaction_id", "model_version_id", "anomaly_score", "is_anomaly", "priority", "explanation"],
                  rows)

    @staticmethod
    def _create_exceptions(conn: Connection, version_id: int, version: str, thresholds: dict[str, float],
                           min_priority: str) -> int:
        """One ML exception per flagged transaction at or above ``min_priority`` (kept across model versions)."""
        cur = conn.execute(
            """INSERT INTO audit_exceptions (exception_ref, detection_source, prediction_id, transaction_id, account_id,
                                             severity, risk_category, reason, details)
               SELECT 'EXC-' || lpad(nextval('audit_exception_ref_seq')::text, 6, '0'), 'ML', p.id, p.transaction_id,
                      t.account_id, p.priority, %(category)s,
                      'Anomaly score ' || to_char(p.anomaly_score, 'FM0.000') || ' (' || initcap(p.priority)
                          || ' priority, model ' || %(version)s || '). The transaction is unusual compared with the '
                          || 'account''s history and the wider population'
                          || coalesce(': ' || (SELECT string_agg(f ->> 'description', '; ')
                                               FROM jsonb_array_elements(p.explanation -> 'factors') f), '')
                          || '. ' || %(note)s,
                      jsonb_build_object('anomalyScore', p.anomaly_score, 'priority', p.priority,
                                         'modelVersion', %(version)s, 'modelVersionId', %(vid)s,
                                         'thresholds', %(thresholds)s::jsonb,
                                         'factors', coalesce(p.explanation -> 'factors', '[]'::jsonb),
                                         'explanationMethod', p.explanation ->> 'method', 'scoreNote', %(note)s)
               FROM anomaly_predictions p JOIN transactions t ON t.transaction_id = p.transaction_id
               WHERE p.model_version_id = %(vid)s AND p.is_anomaly AND p.priority = ANY(%(priorities)s)
               ON CONFLICT (transaction_id) WHERE detection_source = 'ML' DO NOTHING""",
            {"category": RISK_CATEGORY, "version": version, "vid": version_id,
             "priorities": list(PRIORITIES[PRIORITIES.index(min_priority):]),
             "thresholds": json.dumps(thresholds), "note": SCORE_NOTE})
        return cur.rowcount


# ---------------------------------------------------------------------------------------------------------------------
# Evaluation (ground truth used for measurement only)
# ---------------------------------------------------------------------------------------------------------------------
def evaluate(conn: Connection, version_id: int) -> dict:
    meta = conn.execute(
        """SELECT version, trained_at, trained_by, training_rows, hyperparameters, feature_list, priority_thresholds,
                  is_active FROM ml_model_versions WHERE id = %s""", (version_id,)).fetchone()
    version, trained_at, trained_by, rows, hyper, feature_list, model_thresholds, active = meta
    thresholds = read_thresholds(conn)
    cur = conn.execute(
        """SELECT p.transaction_id, p.anomaly_score::float8, l.anomaly_type, t.transaction_type,
                  t.transaction_channel, p.explanation -> 'factors' -> 0 ->> 'feature' AS top_factor,
                  EXISTS (SELECT 1 FROM audit_exceptions e
                          WHERE e.transaction_id = p.transaction_id AND e.detection_source = 'RULE') AS rule_flagged
           FROM anomaly_predictions p
           JOIN transactions t ON t.transaction_id = p.transaction_id
           LEFT JOIN raw.synthetic_anomaly_labels l ON l.transaction_id = p.transaction_id
           WHERE p.model_version_id = %s""", (version_id,))
    df = pd.DataFrame(cur.fetchall(), columns=["tx", "score", "label", "type", "channel", "top_factor", "rule"])
    result: dict = {
        "modelVersionId": version_id, "version": version, "trainedAt": trained_at.isoformat(),
        "trainedBy": trained_by, "trainingRows": rows, "hyperparameters": hyper, "features": feature_list,
        "trainingThresholds": model_thresholds, "thresholds": thresholds, "active": active,
        "exceptionMinPriority": read_exception_priority(conn),
        "algorithm": "IsolationForest", "scoredTransactions": len(df), "scoreNote": SCORE_NOTE,
        "evaluatedAt": datetime.now(UTC).isoformat(),
    }
    result["scoreDistribution"] = _histogram(df)
    labelled = df.label.notna()
    if df.empty or not labelled.any():
        result["groundTruthAvailable"] = False
        result["flagged"] = int((df.score >= thresholds["anomaly"]).sum()) if not df.empty else 0
        return result

    y = labelled.to_numpy()
    flagged = (df.score >= thresholds["anomaly"]).to_numpy()
    rule = df.rule.to_numpy(dtype=bool)
    tn, fp, fn, tp = (int(v) for v in confusion_matrix(y, flagged, labels=[False, True]).ravel())
    result["groundTruthAvailable"] = True
    result["labelledAnomalies"] = int(y.sum())
    result["flagged"] = int(flagged.sum())
    result["confusionMatrix"] = {"truePositive": tp, "falsePositive": fp, "falseNegative": fn, "trueNegative": tn}
    result["metrics"] = _prf(tp, fp, fn) | {
        "rocAuc": round(float(roc_auc_score(y, df.score)), 4),
        "averagePrecision": round(float(average_precision_score(y, df.score)), 4),
    }
    bands = []
    for name, lo, hi in (("HIGH", thresholds["high"], 1.01), ("MEDIUM", thresholds["medium"], thresholds["high"]),
                         ("LOW", thresholds["anomaly"], thresholds["medium"])):
        in_band = (df.score >= lo) & (df.score < hi)
        n = int(in_band.sum())
        hits = int((in_band & labelled).sum())
        bands.append({"priority": name, "from": lo, "flagged": n, "labelledAnomalies": hits,
                      "precision": round(hits / n, 4) if n else None})
    result["priorityBands"] = bands

    by_type = []
    for anomaly_type, group in df[labelled].groupby("label"):
        total = len(group)
        model_hits = int((group.score >= thresholds["anomaly"]).sum())
        rule_hits = int(group.rule.sum())
        by_type.append({"anomalyType": anomaly_type, "labelled": total,
                        "modelRecall": round(model_hits / total, 4), "ruleRecall": round(rule_hits / total, 4)})
    result["recallByType"] = by_type

    rule_ran = bool(rule.any())
    comparison = {"rulesExecuted": rule_ran, "model": result["metrics"] | {"flagged": int(flagged.sum())}}
    if rule_ran:
        r_tp, r_fp, r_fn = int((rule & y).sum()), int((rule & ~y).sum()), int((~rule & y).sum())
        either = rule | flagged
        c_tp, c_fp, c_fn = int((either & y).sum()), int((either & ~y).sum()), int((~either & y).sum())
        comparison["rules"] = _prf(r_tp, r_fp, r_fn) | {"flagged": int(rule.sum())}
        comparison["combined"] = _prf(c_tp, c_fp, c_fn) | {"flagged": int(either.sum())}
        comparison["overlap"] = {
            "bothDetected": int((rule & flagged & y).sum()),
            "rulesOnly": int((rule & ~flagged & y).sum()),
            "modelOnly": int((~rule & flagged & y).sum()),
            "neither": int((~rule & ~flagged & y).sum()),
        }
    result["comparison"] = comparison

    fp_mask = flagged & ~y
    fps = df[fp_mask]
    labels = {f.name: f.label for f in FEATURES}
    result["falsePositives"] = {
        "count": int(fp_mask.sum()),
        "share": round(float(fp_mask.sum() / max(flagged.sum(), 1)), 4),
        "alsoFlaggedByRules": int((fp_mask & rule).sum()),
        "scoreMedian": round(float(fps.score.median()), 4) if len(fps) else None,
        "byTransactionType": _counts(fps.type),
        "byChannel": _counts(fps.channel),
        "byTopFactor": [{"key": labels.get(k, k), "count": v} for k, v in
                        fps.top_factor.fillna("none").value_counts().items()],
    }
    return result


def _prf(tp: int, fp: int, fn: int) -> dict:
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4),
            "truePositive": tp, "falsePositive": fp, "falseNegative": fn}


def _counts(series: pd.Series) -> list[dict]:
    return [{"key": str(k), "count": int(v)} for k, v in series.value_counts().items()]


def _histogram(df: pd.DataFrame) -> list[dict]:
    if df.empty:
        return []
    edges = np.round(np.arange(0.30, 0.85, 0.025), 3)
    clipped = df.score.clip(edges[0], edges[-1] - 1e-9)
    bins = pd.cut(clipped, edges, right=False, labels=edges[:-1])
    labelled = df.label.notna()
    out = []
    for edge in edges[:-1]:
        in_bin = bins == edge
        out.append({"from": float(edge), "to": float(round(edge + 0.025, 3)),
                    "unlabelled": int((in_bin & ~labelled).sum()), "labelled": int((in_bin & labelled).sum())})
    return out


def _snapshot(metrics: dict) -> dict:
    """Compact metrics stored with the model version."""
    keys = ("groundTruthAvailable", "labelledAnomalies", "flagged", "confusionMatrix", "metrics", "priorityBands",
            "recallByType", "trainingDurationMs", "thresholds")
    return {k: metrics[k] for k in keys if k in metrics}


def _check_actor(actor: str) -> None:
    if not actor or len(actor) > 50:
        raise ValueError("The user name must be 1-50 characters")


def _load_artifact(path: str) -> dict:
    file = Path(path)
    if not file.is_file():
        raise NoModelError(f"The model artifact {file.name} is missing; retrain the model.")
    return joblib.load(file)


class _locked:
    """Session-level advisory lock: one training or detection run at a time."""

    def __init__(self, conn: Connection):
        self.conn = conn

    def __enter__(self):
        if not self.conn.execute("SELECT pg_try_advisory_lock(%s)", (LOCK_KEY,)).fetchone()[0]:
            raise ModelBusyError("The anomaly model is already training or scoring. Try again shortly.")
        self.conn.execute("SET statement_timeout = '600s'")
        return self

    def __exit__(self, *exc):
        self.conn.execute("SELECT pg_advisory_unlock(%s)", (LOCK_KEY,))
        return False
