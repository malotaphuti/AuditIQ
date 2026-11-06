"""Anomaly model lifecycle against a real PostgreSQL database. Requires TEST_DATABASE_ADMIN_URL."""

import psycopg
import pytest

from app.ml.model import LOCK_KEY, AnomalyModelService, ModelBusyError, NoModelError, TrainingParams
from app.pipeline.runner import PipelineRunner, RunRequest
from app.rules.engine import RuleEngine
from tests.integration.helpers import fetch


@pytest.fixture(scope="module")
def connect(database_url):
    return lambda: psycopg.connect(database_url, autocommit=True)


@pytest.fixture(scope="module")
def service(connect, tmp_path_factory):
    return AnomalyModelService(connect, tmp_path_factory.mktemp("models"))


@pytest.fixture(scope="module")
def loaded(connect, tmp_path_factory):
    PipelineRunner(connect, tmp_path_factory.mktemp("data")).start(
        RunRequest(source="GENERATED", triggered_by="tester", seed=31, transaction_count=30_000), background=False)
    RuleEngine(connect).execute("tester")
    return True


def test_detection_and_evaluation_need_a_trained_model(service, database_url):
    with pytest.raises(NoModelError):
        service.detect("tester")
    with pytest.raises(NoModelError):
        service.performance()


def test_training_needs_enough_data(service):
    with pytest.raises(ValueError, match="At least"):
        service.train("tester")


@pytest.fixture(scope="module")
def trained(loaded, service):
    return service.train("tester", TrainingParams(n_estimators=100, seed=7))


def test_training_records_a_versioned_active_model_and_scores_every_transaction(trained, database_url):
    (active, algorithm, rows, hyper, features, trained_by), = fetch(database_url, """
        SELECT is_active, algorithm, training_rows, hyperparameters, feature_list, trained_by
        FROM ml_model_versions WHERE id = %s""", trained["modelVersionId"])
    total, = fetch(database_url, "SELECT count(*) FROM transactions")[0]
    assert active and algorithm == "IsolationForest" and trained_by == "tester"
    assert rows == total and hyper["n_estimators"] == 100 and hyper["random_state"] == 7
    assert len(features) == 13
    scored, = fetch(database_url, "SELECT count(*) FROM anomaly_predictions WHERE model_version_id = %s",
                    trained["modelVersionId"])[0]
    assert scored == total
    bad, = fetch(database_url, """SELECT count(*) FROM anomaly_predictions
                                  WHERE anomaly_score <= 0 OR anomaly_score >= 1""")[0]
    assert bad == 0


def test_model_separates_injected_anomalies(trained):
    m = trained["metrics"]
    assert m["groundTruthAvailable"]
    assert m["metrics"]["rocAuc"] > 0.9
    high = next(b for b in m["priorityBands"] if b["priority"] == "HIGH")
    assert high["flagged"] > 0 and high["precision"] > 0.8
    cm = m["confusionMatrix"]
    assert cm["truePositive"] + cm["falseNegative"] == m["labelledAnomalies"]


def test_flagged_predictions_carry_explanations(trained, database_url):
    rows = fetch(database_url, """SELECT priority, explanation FROM anomaly_predictions
                                  WHERE model_version_id = %s AND is_anomaly LIMIT 50""", trained["modelVersionId"])
    assert rows
    for priority, explanation in rows:
        assert priority in ("LOW", "MEDIUM", "HIGH")
        assert explanation["factors"] and explanation["factors"][0]["description"]


def test_training_is_reproducible_for_a_seed(trained, service, database_url):
    again = service.train("tester", TrainingParams(n_estimators=100, seed=7))
    diff, = fetch(database_url, """
        SELECT count(*) FROM anomaly_predictions a JOIN anomaly_predictions b USING (transaction_id)
        WHERE a.model_version_id = %s AND b.model_version_id = %s AND a.anomaly_score <> b.anomaly_score""",
                  trained["modelVersionId"], again["modelVersionId"])[0]
    assert diff == 0
    actives = fetch(database_url, "SELECT id FROM ml_model_versions WHERE is_active")
    assert actives == [(again["modelVersionId"],)]


def test_detection_raises_neutral_exceptions_for_medium_and_high_priority(trained, service, database_url):
    result = service.detect("tester")
    assert result["exceptionMinPriority"] == "MEDIUM"
    assert result["exceptionsCreated"] == result["byPriority"]["HIGH"] + result["byPriority"]["MEDIUM"]
    rows = fetch(database_url, """SELECT severity, risk_category, reason, details, prediction_id, rule_id
                                  FROM audit_exceptions WHERE detection_source = 'ML'""")
    assert len(rows) == result["exceptionsCreated"]
    for severity, category, reason, details, prediction_id, rule_id in rows:
        assert severity in ("MEDIUM", "HIGH") and category == "BEHAVIOURAL_ANOMALY" and rule_id is None
        assert prediction_id is not None
        assert "not a probability of fraud" in reason and "fraud" not in reason.replace("probability of fraud", "")
        assert 0 < details["anomalyScore"] < 1 and details["factors"]
    # Detection is idempotent.
    assert service.detect("tester")["exceptionsCreated"] == 0


def test_new_data_is_scored_by_the_active_model(service, connect, database_url, tmp_path_factory):
    PipelineRunner(connect, tmp_path_factory.mktemp("more")).start(
        RunRequest(source="GENERATED", triggered_by="tester", seed=32, transaction_count=2_000), background=False)
    result = service.detect("tester")
    assert result["newlyScored"] > 1_000
    unscored, = fetch(database_url, """
        SELECT count(*) FROM transactions t WHERE NOT EXISTS (
            SELECT 1 FROM anomaly_predictions p JOIN ml_model_versions m ON m.id = p.model_version_id AND m.is_active
            WHERE p.transaction_id = t.transaction_id)""")[0]
    assert unscored == 0


def test_threshold_changes_are_applied_on_detection(service, connect, database_url):
    with connect() as conn:
        conn.execute("UPDATE system_settings SET setting_value = '0.70' WHERE setting_key = 'ml.anomaly_threshold'")
    try:
        with pytest.raises(ValueError, match="thresholds"):
            service.detect("tester")  # 0.70 > medium threshold: rejected
    finally:
        with connect() as conn:
            conn.execute("UPDATE system_settings SET setting_value = '0.55' WHERE setting_key = 'ml.anomaly_threshold'")
    with connect() as conn:
        conn.execute("UPDATE system_settings SET setting_value = '0.75' "
                     "WHERE setting_key = 'ml.priority_threshold_high'")
    try:
        result = service.detect("tester")
        top, = fetch(database_url, """SELECT min(anomaly_score) FROM anomaly_predictions p
                                      JOIN ml_model_versions m ON m.id = p.model_version_id AND m.is_active
                                      WHERE p.priority = 'HIGH'""")[0]
        assert top is None or float(top) >= 0.75
        assert result["thresholds"]["high"] == 0.75
    finally:
        with connect() as conn:
            conn.execute("UPDATE system_settings SET setting_value = '0.65' "
                         "WHERE setting_key = 'ml.priority_threshold_high'")


def test_performance_compares_the_model_with_the_rules(service):
    p = service.performance()
    assert p["comparison"]["rulesExecuted"]
    overlap = p["comparison"]["overlap"]
    assert sum(overlap.values()) == p["labelledAnomalies"]
    assert {r["anomalyType"] for r in p["recallByType"]} == {
        "DUPLICATE_PAYMENT", "LARGE_TRANSACTION", "SPLIT_TRANSACTION", "HIGH_FREQUENCY",
        "DORMANT_ACCOUNT_ACTIVITY", "UNUSUAL_VENDOR_PAYMENT"}
    assert p["falsePositives"]["count"] == p["confusionMatrix"]["falsePositive"]
    assert len(p["scoreDistribution"]) > 10
    assert "not a probability of fraud" in p["scoreNote"]
    with pytest.raises(NoModelError):
        service.performance(999_999)


def test_one_run_at_a_time(service, connect):
    with connect() as holder:
        holder.execute("SELECT pg_advisory_lock(%s)", (LOCK_KEY,))
        with pytest.raises(ModelBusyError):
            service.detect("tester")


def test_missing_artifact_is_reported(service, connect):
    with connect() as conn:
        path = conn.execute("SELECT artifact_path FROM ml_model_versions WHERE is_active").fetchone()[0]
        conn.execute("UPDATE ml_model_versions SET artifact_path = %s WHERE is_active", (path + ".missing",))
        conn.execute("DELETE FROM anomaly_predictions WHERE transaction_id IN (SELECT transaction_id FROM transactions "
                     "LIMIT 1)")
    try:
        with pytest.raises(NoModelError, match="retrain"):
            service.detect("tester")
    finally:
        with connect() as conn:
            conn.execute("UPDATE ml_model_versions SET artifact_path = %s WHERE is_active", (path,))
