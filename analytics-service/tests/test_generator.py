import pandas as pd
import pytest

from app.pipeline import reference as ref
from app.pipeline.generator import generate_dataset
from app.pipeline.validation import _parse_dates


@pytest.fixture(scope="module")
def dataset():
    return generate_dataset(seed=7, n_transactions=20_000)


def test_full_size_dataset_meets_volume_requirement():
    data = generate_dataset(seed=1, n_transactions=100_000)
    assert len(data.transactions) >= 100_000


def test_generation_is_deterministic_for_a_seed():
    a = generate_dataset(seed=99, n_transactions=2_000)
    b = generate_dataset(seed=99, n_transactions=2_000)
    pd.testing.assert_frame_equal(a.transactions, b.transactions)
    pd.testing.assert_frame_equal(a.labels, b.labels)


def test_different_seeds_produce_different_transactions():
    a = generate_dataset(seed=1, n_transactions=2_000)
    b = generate_dataset(seed=2, n_transactions=2_000)
    assert not set(a.transactions.transaction_id) & set(b.transactions.transaction_id)


def test_master_data_is_stable_across_seeds():
    a = generate_dataset(seed=1, n_transactions=1_000)
    b = generate_dataset(seed=2, n_transactions=1_000)
    pd.testing.assert_frame_equal(a.accounts, b.accounts)
    pd.testing.assert_frame_equal(a.merchants, b.merchants)


def test_raw_feed_has_required_columns_as_strings(dataset):
    assert list(dataset.transactions.columns) == list(ref.RAW_COLUMNS)
    assert all(dataset.transactions[c].map(lambda v: isinstance(v, str)).all() for c in ref.RAW_COLUMNS)


def test_every_anomaly_type_is_injected_and_labelled(dataset):
    counts = dataset.labels.anomaly_type.value_counts()
    assert set(counts.index) == set(ref.ANOMALY_TYPES)
    assert dataset.labels.transaction_id.is_unique
    assert dataset.labels.transaction_id.isin(dataset.transactions.transaction_id).all()


def test_split_transactions_sit_below_threshold_but_exceed_it_together(dataset):
    tx = dataset.transactions
    split = tx[tx.transaction_id.isin(dataset.labels.query("anomaly_type == 'SPLIT_TRANSACTION'").transaction_id)]
    amounts = split.transaction_amount.str.replace(",", "").astype(float)
    thresholds = split.approval_threshold.astype(float)
    assert (amounts < thresholds).all()
    per_account = amounts.groupby(split.account_id.str.strip()).sum()
    limit = thresholds.groupby(split.account_id.str.strip()).first()
    assert (per_account > limit).all()


def test_dormant_activity_only_on_dormant_accounts(dataset):
    dormant = set(dataset.accounts.query("status == 'DORMANT'").account_id)
    ids = dataset.labels.query("anomaly_type == 'DORMANT_ACCOUNT_ACTIVITY'").transaction_id
    accounts = dataset.transactions[dataset.transactions.transaction_id.isin(ids)].account_id.str.strip()
    assert set(accounts) <= dormant
    # Normal (unlabelled) traffic never touches dormant accounts.
    normal = dataset.transactions[~dataset.transactions.transaction_id.isin(dataset.labels.transaction_id)]
    assert not set(normal.account_id.str.strip()) & dormant


def test_duplicate_payments_match_an_original_on_account_amount_and_day(dataset):
    tx = dataset.transactions.drop_duplicates("transaction_id").copy()
    tx["day"] = _parse_dates(tx.transaction_date).dt.tz_convert(ref.LOCAL_TIMEZONE).dt.date
    tx["amt"] = pd.to_numeric(tx.transaction_amount.str.replace(",", ""), errors="coerce")
    tx["acc"] = tx.account_id.str.strip()
    dups = tx[tx.transaction_id.isin(dataset.labels.query("anomaly_type == 'DUPLICATE_PAYMENT'").transaction_id)]
    matched = 0
    for row in dups.itertuples():
        twins = tx[(tx.acc == row.acc) & (tx.amt == row.amt) & (tx.day == row.day)]
        matched += len(twins) >= 2
    assert matched == len(dups)


def test_data_quality_issues_are_present(dataset):
    issues = dataset.stats["data_quality_issues"]
    for key in ("missing_account_id", "missing_amount", "invalid_date", "negative_amount", "duplicate_records",
                "date_format_variants", "channel_synonyms", "foreign_currency"):
        assert issues[key] > 0, key
    assert dataset.transactions.transaction_id.duplicated().sum() == issues["duplicate_records"]


def test_rejects_tiny_volumes():
    with pytest.raises(ValueError):
        generate_dataset(n_transactions=10)


def test_activity_covers_the_whole_twelve_month_period():
    data = generate_dataset(seed=5, n_transactions=20_000)
    days = _parse_dates(data.transactions.transaction_date).dt.tz_convert(ref.LOCAL_TIMEZONE).dt.date.dropna()
    days = days[days.astype(str) < "2030-01-01"]  # ignore the injected future-date defects
    assert str(days.min()) == data.stats["period_start"] == "2025-11-01"
    assert str(days.max()) == data.stats["period_end"] == "2026-10-31"
