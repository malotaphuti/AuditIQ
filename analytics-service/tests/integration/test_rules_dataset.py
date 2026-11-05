"""Rule detection quality on a generated dataset with known injected anomalies (ground truth labels).

The labels are used only to measure the rules – the rules never read them. Requires TEST_DATABASE_ADMIN_URL.
"""

import psycopg
import pytest

from app.pipeline.runner import PipelineRunner, RunRequest
from app.rules.engine import RuleEngine
from tests.integration.helpers import fetch

RULE_FOR_TYPE = {
    "DUPLICATE_PAYMENT": "AR-001",
    "LARGE_TRANSACTION": "AR-002",
    "SPLIT_TRANSACTION": "AR-003",
    "HIGH_FREQUENCY": "AR-004",
    "DORMANT_ACCOUNT_ACTIVITY": "AR-005",
    "UNUSUAL_VENDOR_PAYMENT": "AR-006",
}


@pytest.fixture(scope="module")
def executed(database_url, tmp_path_factory):
    connect = lambda: psycopg.connect(database_url, autocommit=True)  # noqa: E731
    run = PipelineRunner(connect, tmp_path_factory.mktemp("data")).start(
        RunRequest(source="GENERATED", triggered_by="tester", seed=21, transaction_count=30_000), background=False)
    status, = fetch(database_url, "SELECT status FROM pipeline_runs WHERE id = %s", run.run_id)[0]
    assert status in ("SUCCESS", "PARTIAL")
    return RuleEngine(connect).execute("tester")


@pytest.mark.parametrize("anomaly_type", sorted(RULE_FOR_TYPE))
def test_each_rule_recovers_its_injected_anomalies(executed, database_url, anomaly_type):
    rule_id = RULE_FOR_TYPE[anomaly_type]
    (labelled, detected), = fetch(database_url, """
        SELECT count(*), count(e.id) FROM raw.synthetic_anomaly_labels l
        LEFT JOIN audit_exceptions e ON e.transaction_id = l.transaction_id AND e.rule_id = %s
        WHERE l.anomaly_type = %s""", rule_id, anomaly_type)
    assert labelled > 0
    assert detected / labelled >= 0.95, f"{rule_id} recall {detected}/{labelled}"


def test_rules_raise_few_findings_on_ordinary_activity(executed, database_url):
    (total, unlabelled), = fetch(database_url, """
        SELECT count(*), count(*) FILTER (WHERE l.transaction_id IS NULL)
        FROM audit_exceptions e LEFT JOIN raw.synthetic_anomaly_labels l ON l.transaction_id = e.transaction_id""")
    assert total > 0
    assert unlabelled / total < 0.10, f"{unlabelled} of {total} findings are on unlabelled transactions"
