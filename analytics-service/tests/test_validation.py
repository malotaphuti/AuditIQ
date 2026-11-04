from decimal import Decimal

import pandas as pd
import pytest

from app.pipeline import reference as ref
from app.pipeline.generator import generate_dataset
from app.pipeline.validation import ReferenceData, validate

REF = ReferenceData(
    accounts={"ACC-000001": ("CUS-000001", "CHEQUE"), "ACC-000002": ("CUS-000002", "BUSINESS")},
    merchants={"MER-00001": "GROCERIES", "MER-00002": "IT_SERVICES"},
)
NOW = pd.Timestamp("2026-11-01T00:00:00Z")


def row(**overrides) -> dict:
    base = {
        "transaction_id": "TXN1", "account_id": "ACC-000001", "customer_id": "CUS-000001",
        "transaction_date": "2026-03-01 10:00:00", "transaction_amount": "120.50", "currency": "ZAR",
        "transaction_type": "PURCHASE", "transaction_channel": "POS", "merchant_id": "MER-00001",
        "merchant_category": "GROCERIES", "transaction_location": "Durban", "account_type": "CHEQUE",
        "transaction_status": "COMPLETED", "approval_threshold": "50000.00", "approval_status": "NOT_REQUIRED",
    }
    base.update(overrides)
    return base


def run(*rows, loaded=frozenset()):
    raw = pd.DataFrame(rows)
    raw["raw_id"] = range(1, len(raw) + 1)
    return validate(raw, REF, set(loaded), now=NOW)


def single(**overrides):
    result = run(row(**overrides))
    return result, result.staged.iloc[0]


def test_clean_row_is_valid():
    result, s = single()
    assert result.valid_records == 1 and result.rejected_records == 0
    assert s.validation_status == "VALID"
    assert s.rejection_reasons == []


@pytest.mark.parametrize("value", ["2026-03-01 10:00:00", "01/03/2026 10:00:00", "2026-03-01T08:00:00Z"])
def test_all_date_formats_standardise_to_the_same_utc_instant(value):
    _, s = single(transaction_date=value)
    assert s.transaction_date == pd.Timestamp("2026-03-01T08:00:00Z")


@pytest.mark.parametrize(("field", "value", "reason"), [
    ("transaction_date", "2026-02-30 10:00:00", "INVALID_DATE"),
    ("transaction_date", "2030-01-01 10:00:00", "FUTURE_DATE"),
    ("transaction_amount", "N/A", "INVALID_AMOUNT"),
    ("transaction_amount", "-5.00", "NEGATIVE_AMOUNT"),
    ("transaction_amount", "", "MISSING_TRANSACTION_AMOUNT"),
    ("account_id", "", "MISSING_ACCOUNT_ID"),
    ("account_id", "ACC-999999", "UNKNOWN_ACCOUNT"),
    ("customer_id", "CUS-000099", "CUSTOMER_ACCOUNT_MISMATCH"),
    ("account_type", "SAVINGS", "ACCOUNT_TYPE_MISMATCH"),
    ("merchant_id", "MER-99999", "UNKNOWN_MERCHANT"),
    ("merchant_id", "", "MISSING_MERCHANT"),
    ("transaction_channel", "CARRIER PIGEON", "INVALID_CHANNEL"),
    ("transaction_status", "UNKNOWN", "INVALID_STATUS"),
    ("currency", "XYZ", "INVALID_CURRENCY"),
    ("approval_status", "", "MISSING_APPROVAL_STATUS"),
])
def test_invalid_values_are_rejected_with_reason(field, value, reason):
    result, s = single(**{field: value})
    assert s.validation_status == "REJECTED"
    assert reason in s.rejection_reasons
    assert result.rejection_reasons[reason] == 1


def test_synonyms_case_and_whitespace_are_standardised():
    _, s = single(transaction_channel=" Point Of Sale ", transaction_type="Purchase", account_id=" acc-000001 ",
                  merchant_category="groceries ", transaction_status="Settled")
    assert s.validation_status == "VALID"
    assert (s.transaction_channel, s.transaction_type, s.account_id, s.merchant_category, s.transaction_status) == (
        "POS", "PURCHASE", "ACC-000001", "GROCERIES", "COMPLETED")


def test_thousand_separators_are_parsed():
    _, s = single(transaction_amount="12,345.60")
    assert s.transaction_amount == pytest.approx(12345.60)


def test_foreign_currency_is_converted_to_zar_and_original_kept():
    _, s = single(transaction_amount="100.00", currency="usd")
    assert s.currency == "ZAR"
    assert s.transaction_amount == pytest.approx(100 * float(ref.DEFAULT_FX_TO_ZAR["USD"]))
    assert (s.original_amount, s.original_currency) == (100.0, "USD")


def test_custom_fx_rates_are_used():
    raw = pd.DataFrame([row(transaction_amount="10.00", currency="EUR")]).assign(raw_id=1)
    reference = ReferenceData(REF.accounts, REF.merchants, {"ZAR": Decimal(1), "EUR": Decimal("20")})
    assert validate(raw, reference, set(), now=NOW).staged.iloc[0].transaction_amount == 200.0


def test_blank_currency_defaults_to_zar():
    result, s = single(currency="")
    assert s.currency == "ZAR" and result.standardisations["currency_defaulted_to_zar"] == 1


def test_missing_category_is_enriched_from_merchant_master():
    result, s = single(merchant_category="")
    assert s.validation_status == "VALID" and s.merchant_category == "GROCERIES"
    assert result.missing_values["merchant_category"] == 1


def test_merchantless_types_need_no_merchant():
    _, s = single(transaction_type="TRANSFER", merchant_id="", merchant_category="", transaction_channel="ONLINE")
    assert s.validation_status == "VALID" and pd.isna(s.merchant_id)


def test_blank_threshold_defaults_from_account_type():
    _, s = single(approval_threshold="")
    assert s.approval_threshold == float(ref.APPROVAL_THRESHOLDS["CHEQUE"])


def test_duplicates_in_batch_and_already_loaded_are_flagged_not_rejected():
    result = run(row(), row(), row(transaction_id="TXN2"), loaded={"TXN2"})
    statuses = result.staged.validation_status.tolist()
    assert statuses == ["VALID", "DUPLICATE", "DUPLICATE"]
    assert result.staged.rejection_reasons.tolist()[1:] == [["DUPLICATE_IN_BATCH"], ["ALREADY_LOADED"]]
    assert (result.valid_records, result.duplicate_records, result.rejected_records) == (1, 2, 0)


def test_multiple_reasons_are_recorded_on_one_row():
    _, s = single(transaction_amount="-1", transaction_channel="", account_id="ACC-999999")
    assert {"NEGATIVE_AMOUNT", "MISSING_TRANSACTION_CHANNEL", "UNKNOWN_ACCOUNT"} <= set(s.rejection_reasons)


def test_counts_reconcile_on_generated_feed():
    data = generate_dataset(seed=3, n_transactions=10_000)
    raw = data.transactions.assign(raw_id=range(len(data.transactions)))
    reference = ReferenceData({r.account_id: (r.customer_id, r.account_type) for r in data.accounts.itertuples()},
                              dict(zip(data.merchants.merchant_id, data.merchants.merchant_category, strict=True)))
    result = validate(raw, reference, set(), now=NOW)
    assert result.valid_records + result.rejected_records + result.duplicate_records == result.total_records
    assert result.duplicate_records == data.stats["data_quality_issues"]["duplicate_records"]
    # Every injected anomaly survives validation so it can be detected downstream.
    valid_ids = set(result.staged.query("validation_status == 'VALID'").transaction_id)
    assert set(data.labels.transaction_id) <= valid_ids
    # Every valid row is fully standardised.
    valid = result.staged[result.staged.validation_status == "VALID"]
    assert valid.transaction_channel.isin(ref.CHANNELS).all()
    assert valid.transaction_type.isin(ref.TRANSACTION_TYPES).all()
    assert (valid.currency == "ZAR").all()
    assert valid.merchant_category.dropna().isin(ref.MERCHANT_CATEGORIES).all()
