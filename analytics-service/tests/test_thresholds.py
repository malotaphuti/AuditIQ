import pytest

from app.rules.definitions import RULE_QUERIES
from app.rules.thresholds import ThresholdError, validate_thresholds

SCHEMA = {
    "window": {"type": "integer", "min": 1, "max": 48},
    "ratio": {"type": "number", "min": 0.1, "max": 0.99},
    "flag": {"type": "boolean"},
    "types": {"type": "list", "allowed": ["A", "B"]},
}
GOOD = {"window": 24, "ratio": 0.6, "flag": True, "types": ["A"]}


def test_valid_thresholds_pass_and_are_normalised():
    assert validate_thresholds({**GOOD, "window": 24.0, "types": ["A", "A", "B"]}, SCHEMA) == {
        "window": 24, "ratio": 0.6, "flag": True, "types": ["A", "B"]}


@pytest.mark.parametrize(("change", "message"), [
    ({"window": 0}, "at least 1"),
    ({"window": 49}, "at most 48"),
    ({"window": 2.5}, "whole number"),
    ({"window": "24"}, "must be a number"),
    ({"window": True}, "must be a number"),
    ({"ratio": 1.5}, "at most 0.99"),
    ({"flag": "yes"}, "true or false"),
    ({"types": []}, "non-empty list"),
    ({"types": ["C"]}, "unsupported value"),
    ({"extra": 1}, "unknown threshold"),
])
def test_invalid_thresholds_are_rejected(change, message):
    with pytest.raises(ThresholdError, match=message):
        validate_thresholds({**GOOD, **change}, SCHEMA)


def test_missing_threshold_is_rejected():
    with pytest.raises(ThresholdError, match="missing threshold 'flag'"):
        validate_thresholds({k: v for k, v in GOOD.items() if k != "flag"}, SCHEMA)


def test_every_rule_query_returns_the_finding_columns_and_uses_bound_parameters():
    assert sorted(RULE_QUERIES) == ["AR-001", "AR-002", "AR-003", "AR-004", "AR-005", "AR-006"]
    for query in RULE_QUERIES.values():
        sql = query.sql
        for column in ("transaction_id", "account_id", "severity", "reason", "details"):
            assert column in sql
        # Thresholds are only ever bound parameters – never formatted into the SQL text.
        assert "{" not in sql and "}" not in sql
