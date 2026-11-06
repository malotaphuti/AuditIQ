import numpy as np
import pandas as pd
import pytest

from app.ml.features import CONTEXT_SQL, FEATURE_NAMES, ReferenceStats, build_features, explain
from app.ml.model import TrainingParams, priority_for


def context(**overrides) -> pd.DataFrame:
    """Five transactions on one account: four ordinary purchases and one large night-time transfer."""
    base = {
        "transaction_id": ["T1", "T2", "T3", "T4", "T5"],
        "account_id": ["A"] * 5,
        "amount": [100.0, 120.0, 90.0, 110.0, 20_000.0],
        "threshold": [50_000.0] * 5,
        "channel": ["POS", "POS", "POS", "POS", "ONLINE"],
        "category": ["GROCERIES"] * 4 + ["NONE"],
        "tx_type": ["PURCHASE"] * 4 + ["TRANSFER"],
        "merchant_id": ["M1", "M1", "M1", "M1", None],
        "status": ["COMPLETED"] * 4 + ["FAILED"],
        "local_hour": [10, 11, 12, 13, 2],
        "typical_amount": [100.0] * 4 + [2_000.0],
        "tx_last_hour": [0, 0, 0, 0, 3],
        "tx_last_24h": [0, 1, 2, 3, 4],
        "gap_hours": [24.0, 24.0, 24.0, 24.0, 1.0],
        "account_count": [5] * 5,
    }
    base.update(overrides)
    frame = pd.DataFrame(base)
    log_amount = np.log1p(frame.amount)
    frame["account_log_sum"] = log_amount.sum()
    frame["account_log_sumsq"] = (log_amount ** 2).sum()
    frame["account_channel_count"] = frame.groupby("channel").transaction_id.transform("count")
    frame["account_category_count"] = frame.groupby("category").transaction_id.transform("count")
    frame["payee_count"] = frame.groupby(frame.merchant_id.fillna("-")).transaction_id.transform("count")
    return frame


def test_features_describe_deviation_from_the_account_history():
    f = build_features(context())
    assert list(f.columns) == FEATURE_NAMES
    assert not f.isna().any().any()
    # The large transfer deviates strongly from the account's other transactions (leave-one-out).
    assert f.account_amount_deviation.iloc[4] > 5
    assert abs(f.account_amount_deviation.iloc[0]) < 2
    assert f.channel_rarity.iloc[4] == pytest.approx(0.8)
    assert f.night_time.tolist() == [0, 0, 0, 0, 1]
    assert f.failed_or_reversed.iloc[4] == 1
    assert f.payee_novelty.iloc[0] == pytest.approx(0.25) and f.payee_novelty.iloc[4] == 0
    assert f.amount_to_threshold.iloc[4] == pytest.approx(0.4)


def test_explanations_name_the_most_unusual_features_in_plain_language():
    ctx = context()
    f = build_features(ctx)
    stats = ReferenceStats.fit(f)
    factors = explain(f.iloc[[4]], ctx.iloc[[4]], stats)[0]
    assert 1 <= len(factors) <= 3
    assert all({"feature", "label", "description", "deviation"} <= set(x) for x in factors)
    assert factors == sorted(factors, key=lambda x: -x["deviation"])
    assert all("fraud" not in x["description"].lower() for x in factors)
    # An ordinary transaction has no notable factors, unless the caller needs the leading one for a flagged row.
    assert explain(f.iloc[[1]], ctx.iloc[[1]], stats)[0] == []
    forced = explain(f.iloc[[1]], ctx.iloc[[1]], stats, at_least_one=True)[0]
    assert len(forced) == 1 and forced[0]["combined"] is True


def test_low_values_are_not_reported_as_unusual_for_one_directional_features():
    f = build_features(context())
    z = ReferenceStats.fit(f).deviations(f)
    assert (z >= 0).all().all()
    assert z.tx_last_hour.iloc[0] == 0


def test_priority_bands():
    t = {"anomaly": 0.55, "medium": 0.60, "high": 0.65}
    assert priority_for(0.54, t) is None
    assert priority_for(0.55, t) == "LOW"
    assert priority_for(0.62, t) == "MEDIUM"
    assert priority_for(0.65, t) == "HIGH"


def test_training_parameters_are_bounded():
    assert TrainingParams().validated().n_estimators == 200
    for bad in (TrainingParams(n_estimators=5), TrainingParams(max_samples=10), TrainingParams(seed=-1)):
        with pytest.raises(ValueError):
            bad.validated()


def test_the_model_never_reads_ground_truth_labels():
    assert "synthetic_anomaly_labels" not in CONTEXT_SQL
    assert "raw." not in CONTEXT_SQL
