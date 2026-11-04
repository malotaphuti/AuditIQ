"""Validation and standardisation of the raw transaction feed (raw layer -> staging layer).

Pure pandas, no database access, so every rule is unit-testable. Each input row receives exactly one status:

* ``DUPLICATE`` – the transaction_id was already seen earlier in the batch or is already in the curated layer.
* ``REJECTED``  – one or more validation rules failed (reasons are recorded per row).
* ``VALID``     – standardised and ready for the curated layer.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from decimal import Decimal

import numpy as np
import pandas as pd

from app.pipeline import reference as ref

MERCHANT_REQUIRED_TYPES = frozenset({"PURCHASE", "VENDOR_PAYMENT", "DEBIT_ORDER"})
FIELD_LIMITS = {"transaction_id": 40, "account_id": 20, "customer_id": 20, "merchant_id": 20}


@dataclass
class ReferenceData:
    accounts: dict[str, tuple[str, str]]  # account_id -> (customer_id, account_type)
    merchants: dict[str, str]  # merchant_id -> merchant_category
    fx_to_zar: dict[str, Decimal] = field(default_factory=lambda: dict(ref.DEFAULT_FX_TO_ZAR))


@dataclass
class ValidationResult:
    staged: pd.DataFrame
    total_records: int
    valid_records: int
    rejected_records: int
    duplicate_records: int
    missing_values: dict[str, int]
    rejection_reasons: dict[str, int]
    standardisations: dict[str, int]

    @property
    def missing_value_total(self) -> int:
        return sum(self.missing_values.values())


def _norm_token(series: pd.Series) -> pd.Series:
    """Upper-case, trim and collapse internal whitespace."""
    return series.str.strip().str.upper().str.replace(r"\s+", " ", regex=True)


def _map_vocab(series: pd.Series, synonyms: dict[str, str], allowed: tuple[str, ...]) -> tuple[pd.Series, pd.Series]:
    """Returns (standardised values, mask of values that changed spelling). Unknown values become NaN."""
    token = _norm_token(series)
    mapped = token.map(lambda v: synonyms.get(v, v.replace(" ", "_")) if v else v)
    valid = mapped.where(mapped.isin(allowed))
    changed = valid.notna() & (valid != series)
    return valid, changed


def _parse_dates(series: pd.Series) -> pd.Series:
    """Accepts 'YYYY-MM-DD HH:MM:SS' and 'DD/MM/YYYY HH:MM:SS' (local SAST) and ISO-8601 UTC ('...T...Z')."""
    s = series.str.strip()
    local = pd.to_datetime(s, format="%Y-%m-%d %H:%M:%S", errors="coerce")
    local = local.fillna(pd.to_datetime(s, format="%d/%m/%Y %H:%M:%S", errors="coerce"))
    local_utc = local.dt.tz_localize(ref.LOCAL_TIMEZONE, ambiguous="NaT", nonexistent="NaT").dt.tz_convert("UTC")
    iso_mask = s.str.contains("T", regex=False) & s.str.endswith("Z")
    iso = pd.to_datetime(s.where(iso_mask), format="%Y-%m-%dT%H:%M:%SZ", errors="coerce", utc=True)
    return local_utc.fillna(iso)


def _to_number(series: pd.Series) -> pd.Series:
    cleaned = series.str.strip().str.replace(",", "", regex=False).str.replace(" ", "", regex=False)
    return pd.to_numeric(cleaned.where(cleaned != ""), errors="coerce")


def validate(raw: pd.DataFrame, reference_data: ReferenceData, already_loaded: set[str],
             now: pd.Timestamp | None = None) -> ValidationResult:
    """Validate and standardise a raw batch. ``raw`` must contain every column in ``reference.RAW_COLUMNS``
    as strings (blank = missing) plus ``raw_id``."""
    now = now or pd.Timestamp.now(tz="UTC")
    df = raw.copy()
    for column in ref.RAW_COLUMNS:
        df[column] = df[column].fillna("").astype(str)
    n = len(df)
    reasons: dict[str, np.ndarray] = {}
    std: Counter[str] = Counter()

    def fail(reason: str, mask: pd.Series | np.ndarray) -> None:
        mask = np.asarray(mask, dtype=bool)
        if mask.any():
            reasons[reason] = reasons.get(reason, np.zeros(n, dtype=bool)) | mask

    stripped = {c: df[c].str.strip() for c in ref.RAW_COLUMNS}
    blank = {c: stripped[c] == "" for c in ref.RAW_COLUMNS}
    std["whitespace_trimmed"] = int(sum(((df[c] != stripped[c]) & ~blank[c]).sum() for c in ref.RAW_COLUMNS))

    # --- identifiers -----------------------------------------------------------------------------------------------
    tx_id = stripped["transaction_id"]
    account_id = stripped["account_id"].str.upper()
    customer_id = stripped["customer_id"].str.upper()
    merchant_id = stripped["merchant_id"].str.upper()
    for column, series in (("transaction_id", tx_id), ("account_id", account_id), ("customer_id", customer_id),
                           ("merchant_id", merchant_id)):
        fail(f"INVALID_{column.upper()}_LENGTH", series.str.len() > FIELD_LIMITS[column])

    for column in ref.REQUIRED_FIELDS:
        fail(f"MISSING_{column.upper()}", blank[column])

    # --- master-data checks ----------------------------------------------------------------------------------------
    known_account = account_id.isin(reference_data.accounts)
    fail("UNKNOWN_ACCOUNT", ~blank["account_id"] & ~known_account)
    master_customer = account_id.map(lambda a: reference_data.accounts.get(a, (None, None))[0])
    master_type = account_id.map(lambda a: reference_data.accounts.get(a, (None, None))[1])
    fail("CUSTOMER_ACCOUNT_MISMATCH", known_account & ~blank["customer_id"] & (customer_id != master_customer))

    # --- dates ------------------------------------------------------------------------------------------------------
    parsed = _parse_dates(df["transaction_date"])
    fail("INVALID_DATE", ~blank["transaction_date"] & parsed.isna())
    fail("FUTURE_DATE", parsed > now + pd.Timedelta(days=1))
    std["dates_standardised"] = int((parsed.notna() & ~stripped["transaction_date"].str.match(
        r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")).sum())

    # --- amounts and currency ---------------------------------------------------------------------------------------
    amount = _to_number(df["transaction_amount"])
    fail("INVALID_AMOUNT", ~blank["transaction_amount"] & amount.isna())
    fail("NEGATIVE_AMOUNT", amount < 0)
    fail("IMPLAUSIBLE_AMOUNT", amount > float(ref.MAX_PLAUSIBLE_AMOUNT))
    std["amount_formats_standardised"] = int((amount.notna() & stripped["transaction_amount"].str.contains(",")).sum())

    currency = stripped["currency"].str.upper()
    std["currency_defaulted_to_zar"] = int(blank["currency"].sum())
    std["currency_case_standardised"] = int((~blank["currency"] & (currency != stripped["currency"])).sum())
    currency = currency.where(~blank["currency"], "ZAR")
    fail("INVALID_CURRENCY", ~currency.isin(reference_data.fx_to_zar))
    rate = currency.map(lambda c: float(reference_data.fx_to_zar.get(c, Decimal("NaN"))))
    amount_zar = (amount * rate).round(2)
    std["currency_converted_to_zar"] = int((amount.notna() & currency.isin(reference_data.fx_to_zar)
                                            & (currency != "ZAR")).sum())

    # --- controlled vocabularies ------------------------------------------------------------------------------------
    tx_type, changed = _map_vocab(df["transaction_type"], ref.TYPE_SYNONYMS, ref.TRANSACTION_TYPES)
    fail("INVALID_TRANSACTION_TYPE", ~blank["transaction_type"] & tx_type.isna())
    std["transaction_types_standardised"] = int(changed.sum())

    channel, changed = _map_vocab(df["transaction_channel"], ref.CHANNEL_SYNONYMS, ref.CHANNELS)
    fail("INVALID_CHANNEL", ~blank["transaction_channel"] & channel.isna())
    std["channels_standardised"] = int(changed.sum())

    status, changed = _map_vocab(df["transaction_status"], ref.STATUS_SYNONYMS, ref.STATUSES)
    fail("INVALID_STATUS", ~blank["transaction_status"] & status.isna())
    std["statuses_standardised"] = int(changed.sum())

    account_type, _ = _map_vocab(df["account_type"], {}, ref.ACCOUNT_TYPES)
    fail("INVALID_ACCOUNT_TYPE", ~blank["account_type"] & account_type.isna())
    fail("ACCOUNT_TYPE_MISMATCH", known_account & account_type.notna() & (account_type != master_type))

    approval, _ = _map_vocab(df["approval_status"], {}, ref.APPROVAL_STATUSES)
    fail("MISSING_APPROVAL_STATUS", blank["approval_status"])
    fail("INVALID_APPROVAL_STATUS", ~blank["approval_status"] & approval.isna())

    threshold = _to_number(df["approval_threshold"])
    fail("INVALID_APPROVAL_THRESHOLD", ~blank["approval_threshold"] & (threshold.isna() | (threshold < 0)))
    default_threshold = account_type.map(lambda t: float(ref.APPROVAL_THRESHOLDS[t]) if t in ref.APPROVAL_THRESHOLDS
                                         else np.nan)
    std["approval_threshold_defaulted"] = int((blank["approval_threshold"] & default_threshold.notna()).sum())
    threshold = threshold.where(~blank["approval_threshold"], default_threshold)

    # --- merchants and categories -----------------------------------------------------------------------------------
    merchant = merchant_id.where(~blank["merchant_id"])
    needs_merchant = tx_type.isin(MERCHANT_REQUIRED_TYPES)
    fail("MISSING_MERCHANT", needs_merchant & merchant.isna())
    known_merchant = merchant.isin(reference_data.merchants)
    fail("UNKNOWN_MERCHANT", merchant.notna() & ~known_merchant)

    category_raw = _norm_token(df["merchant_category"]).str.replace(" ", "_")
    master_category = merchant.map(reference_data.merchants)
    category = master_category.where(known_merchant, category_raw.where(category_raw.isin(ref.MERCHANT_CATEGORIES)))
    std["categories_enriched_from_master"] = int((known_merchant & blank["merchant_category"]).sum())
    std["categories_standardised"] = int((known_merchant & ~blank["merchant_category"]
                                          & (df["merchant_category"] != master_category)).sum())
    fail("INVALID_CATEGORY", ~known_merchant & ~blank["merchant_category"] & category.isna())

    location = stripped["transaction_location"].where(~blank["transaction_location"])

    # --- duplicates -------------------------------------------------------------------------------------------------
    in_batch_dup = tx_id.ne("") & tx_id.duplicated(keep="first")
    already = tx_id.isin(already_loaded) & ~in_batch_dup
    duplicate = (in_batch_dup | already).to_numpy()

    # --- assemble ---------------------------------------------------------------------------------------------------
    reason_names = sorted(reasons)
    matrix = np.column_stack([reasons[r] for r in reason_names]) if reason_names else np.zeros((n, 0), dtype=bool)
    row_reasons = [[reason_names[j] for j in np.flatnonzero(row)] for row in matrix]
    for i in np.flatnonzero(duplicate):
        row_reasons[i] = ["DUPLICATE_IN_BATCH" if in_batch_dup.iat[i] else "ALREADY_LOADED"]
    rejected = ~duplicate & (matrix.any(axis=1) if reason_names else np.zeros(n, dtype=bool))
    validation_status = np.where(duplicate, "DUPLICATE", np.where(rejected, "REJECTED", "VALID"))

    def clip(series: pd.Series, limit: int) -> pd.Series:
        return series.where(series != "").str.slice(0, limit)

    staged = pd.DataFrame({
        "raw_id": df["raw_id"].to_numpy() if "raw_id" in df else np.full(n, None),
        "transaction_id": clip(tx_id, 40),
        "account_id": clip(account_id, 20),
        "customer_id": clip(customer_id, 20),
        "transaction_date": parsed,
        "transaction_amount": amount_zar,
        "currency": np.where(currency.isin(reference_data.fx_to_zar), "ZAR", None),
        "original_amount": amount.round(2),
        "original_currency": clip(currency, 3),
        "transaction_type": tx_type,
        "transaction_channel": channel,
        "merchant_id": merchant.str.slice(0, 20),
        "merchant_category": category,
        "transaction_location": location.str.slice(0, 80),
        "account_type": account_type,
        "transaction_status": status,
        "approval_threshold": threshold.round(2),
        "approval_status": approval,
        "validation_status": validation_status,
        "rejection_reasons": row_reasons,
    })

    rejection_counts: Counter[str] = Counter()
    for row, is_rejected in zip(row_reasons, rejected, strict=True):
        if is_rejected:
            rejection_counts.update(row)

    missing = {c: int(blank[c].sum()) for c in ref.REQUIRED_FIELDS + ("currency", "approval_threshold",
                                                                      "approval_status", "transaction_location")}
    missing["merchant_id"] = int((needs_merchant & blank["merchant_id"]).sum())
    missing["merchant_category"] = int((merchant.notna() & blank["merchant_category"]).sum())

    return ValidationResult(
        staged=staged,
        total_records=n,
        valid_records=int((validation_status == "VALID").sum()),
        rejected_records=int(rejected.sum()),
        duplicate_records=int(duplicate.sum()),
        missing_values={k: v for k, v in missing.items() if v},
        rejection_reasons=dict(rejection_counts.most_common()),
        standardisations={k: v for k, v in std.items() if v},
    )
