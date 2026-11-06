"""Behavioural features for anomaly detection.

Every feature describes how a transaction compares with the account's own history or with the wider population. The
raw context (counts, gaps, account statistics) is computed in PostgreSQL with window functions over the curated
layer; the final transformations are done in pandas.

Ground-truth anomaly labels are never read here – the model is unsupervised.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from psycopg import Connection

LOCAL_TZ = "Africa/Johannesburg"


@dataclass(frozen=True)
class Feature:
    name: str
    label: str
    # Direction in which a value is unusual: "high" (large values are unusual) or "both".
    direction: str = "high"


FEATURES: tuple[Feature, ...] = (
    Feature("log_amount", "Transaction amount"),
    Feature("amount_to_threshold", "Amount relative to the approval threshold"),
    Feature("account_amount_deviation", "Amount compared with the account's other transactions", "both"),
    Feature("category_amount_deviation", "Amount compared with similar transactions", "both"),
    Feature("tx_last_hour", "Transactions on the account in the preceding hour"),
    Feature("tx_last_24h", "Transactions on the account in the preceding 24 hours"),
    Feature("log_gap_hours", "Time since the account's previous activity"),
    Feature("account_activity", "Account activity level (transactions in the data)", "both"),
    Feature("channel_rarity", "How unusual the channel is for the account"),
    Feature("category_rarity", "How unusual the merchant category is for the account"),
    Feature("payee_novelty", "How new the merchant or vendor is to the account"),
    Feature("night_time", "Made between 00:00 and 05:00 (SAST)"),
    Feature("failed_or_reversed", "Failed or reversed transaction"),
)
FEATURE_NAMES = [f.name for f in FEATURES]
# A factor is reported only when it is at least one robust standard deviation from the typical value.
MIN_FACTOR_DEVIATION = 1.0

CONTEXT_SQL = f"""
WITH base AS (
    SELECT t.transaction_id, t.account_id, t.transaction_date, t.transaction_amount::float8 AS amount,
           coalesce(t.approval_threshold, 0)::float8 AS threshold, t.transaction_channel AS channel,
           coalesce(t.merchant_category, 'NONE') AS category, t.transaction_type AS tx_type, t.merchant_id,
           t.transaction_status AS status, a.last_activity_date,
           extract(hour FROM t.transaction_date AT TIME ZONE '{LOCAL_TZ}')::int AS local_hour
    FROM transactions t JOIN accounts a ON a.account_id = t.account_id
), typical AS (
    SELECT category, tx_type, percentile_cont(0.5) WITHIN GROUP (ORDER BY amount) AS typical_amount
    FROM base GROUP BY category, tx_type
)
SELECT b.transaction_id, b.account_id, b.amount, b.threshold, b.channel, b.category, b.tx_type, b.merchant_id,
       b.status, b.local_hour, ty.typical_amount,
       count(*) OVER (PARTITION BY b.account_id ORDER BY b.transaction_date
                      RANGE BETWEEN interval '1 hour' PRECEDING AND CURRENT ROW) - 1 AS tx_last_hour,
       count(*) OVER (PARTITION BY b.account_id ORDER BY b.transaction_date
                      RANGE BETWEEN interval '24 hours' PRECEDING AND CURRENT ROW) - 1 AS tx_last_24h,
       extract(epoch FROM b.transaction_date - coalesce(
           lag(b.transaction_date) OVER (PARTITION BY b.account_id ORDER BY b.transaction_date, b.transaction_id),
           (b.last_activity_date::timestamp AT TIME ZONE '{LOCAL_TZ}'))) / 3600.0 AS gap_hours,
       count(*) OVER (PARTITION BY b.account_id) AS account_count,
       sum(ln(1 + b.amount)) OVER (PARTITION BY b.account_id) AS account_log_sum,
       sum(ln(1 + b.amount) ^ 2) OVER (PARTITION BY b.account_id) AS account_log_sumsq,
       count(*) OVER (PARTITION BY b.account_id, b.channel) AS account_channel_count,
       count(*) OVER (PARTITION BY b.account_id, b.category) AS account_category_count,
       count(*) OVER (PARTITION BY b.account_id, b.merchant_id) AS payee_count
FROM base b JOIN typical ty ON ty.category = b.category AND ty.tx_type = b.tx_type
"""


def load_context(conn: Connection) -> pd.DataFrame:
    cur = conn.execute(CONTEXT_SQL)
    columns = [d.name for d in cur.description]
    frame = pd.DataFrame(cur.fetchall(), columns=columns)
    for col in ("amount", "threshold", "typical_amount", "gap_hours", "account_log_sum", "account_log_sumsq"):
        frame[col] = pd.to_numeric(frame[col], errors="coerce").astype(float)
    return frame


def build_features(ctx: pd.DataFrame) -> pd.DataFrame:
    """Feature matrix (one row per transaction, indexed like ``ctx``)."""
    log_amount = np.log1p(ctx.amount)
    n = ctx.account_count.astype(float)
    # Leave-one-out statistics: compare each transaction with the account's *other* transactions.
    others = (n - 1).clip(lower=1)
    loo_mean = (ctx.account_log_sum - log_amount) / others
    loo_var = ((ctx.account_log_sumsq - log_amount ** 2) / others - loo_mean ** 2).clip(lower=0)
    loo_std = np.sqrt(loo_var).clip(lower=0.25)
    account_dev = np.where(n >= 3, (log_amount - loo_mean) / loo_std, 0.0)

    features = pd.DataFrame(index=ctx.index)
    features["log_amount"] = log_amount
    features["amount_to_threshold"] = np.where(ctx.threshold > 0, ctx.amount / ctx.threshold, 0.0)
    features["account_amount_deviation"] = account_dev
    features["category_amount_deviation"] = log_amount - np.log1p(ctx.typical_amount)
    features["tx_last_hour"] = ctx.tx_last_hour.astype(float)
    features["tx_last_24h"] = ctx.tx_last_24h.astype(float)
    features["log_gap_hours"] = np.log1p(ctx.gap_hours.fillna(0).clip(lower=0))
    features["account_activity"] = np.log1p(n)
    features["channel_rarity"] = 1 - ctx.account_channel_count / n
    features["category_rarity"] = 1 - ctx.account_category_count / n
    features["payee_novelty"] = np.where(ctx.merchant_id.notna(), 1 / ctx.payee_count.clip(lower=1), 0.0)
    features["night_time"] = (ctx.local_hour < 5).astype(float)
    features["failed_or_reversed"] = ctx.status.isin(["FAILED", "REVERSED"]).astype(float)
    return features[FEATURE_NAMES].astype(float)


# ---------------------------------------------------------------------------------------------------------------------
# Explanations
# ---------------------------------------------------------------------------------------------------------------------
@dataclass
class ReferenceStats:
    """Robust statistics of the training population used to describe how unusual a value is."""
    median: dict[str, float]
    scale: dict[str, float]

    @classmethod
    def fit(cls, features: pd.DataFrame) -> ReferenceStats:
        median = features.median()
        iqr = features.quantile(0.75) - features.quantile(0.25)
        scale = (iqr / 1.349).where(iqr > 0, features.std()).replace(0, 1.0).fillna(1.0)
        return cls({k: float(v) for k, v in median.items()}, {k: float(v) for k, v in scale.items()})

    def deviations(self, features: pd.DataFrame) -> pd.DataFrame:
        med = pd.Series(self.median)[features.columns]
        scale = pd.Series(self.scale)[features.columns]
        z = (features - med) / scale
        for f in FEATURES:
            if f.direction == "high":
                z[f.name] = z[f.name].clip(lower=0)
            else:
                z[f.name] = z[f.name].abs()
        return z


def _money(value: float) -> str:
    return f"R {value:,.2f}"


def describe(feature: str, row: pd.Series, ctx: pd.Series) -> str:
    """Plain-language description of a factor using the transaction's own values."""
    match feature:
        case "log_amount":
            return f"Amount of {_money(ctx.amount)} is large relative to all transactions"
        case "amount_to_threshold":
            return f"Amount is {row.amount_to_threshold:.2f} times the {_money(ctx.threshold)} approval threshold"
        case "account_amount_deviation":
            ratio = float(np.expm1(np.log1p(ctx.amount) - (ctx.account_log_sum - np.log1p(ctx.amount))
                                   / max(ctx.account_count - 1, 1)) + 1)
            return (f"Amount is {ratio:,.1f} times the account's typical amount" if ratio >= 1 else
                    f"Amount is well below the account's typical amount ({ratio:.2f} times)")
        case "category_amount_deviation":
            return (f"Amount differs from the typical {ctx.tx_type.lower().replace('_', ' ')} "
                    f"({_money(ctx.typical_amount)}) for this category")
        case "tx_last_hour":
            return f"{int(ctx.tx_last_hour)} other transactions on the account in the preceding hour"
        case "tx_last_24h":
            return f"{int(ctx.tx_last_24h)} other transactions on the account in the preceding 24 hours"
        case "log_gap_hours":
            days = (ctx.gap_hours or 0) / 24
            return f"{days:,.0f} days since the account's previous activity"
        case "account_activity":
            return f"Account has {int(ctx.account_count)} transactions in the data (unusual activity level)"
        case "channel_rarity":
            return f"Channel {ctx.channel} is rarely used by this account"
        case "category_rarity":
            return f"Category {ctx.category.lower().replace('_', ' ')} is unusual for this account"
        case "payee_novelty":
            return f"First or rare payment to merchant {ctx.merchant_id}"
        case "night_time":
            return f"Made at {int(ctx.local_hour):02d}:00 SAST, outside normal hours"
        case "failed_or_reversed":
            return f"Transaction status is {ctx.status}"
    return feature


def explain(features: pd.DataFrame, ctx: pd.DataFrame, stats: ReferenceStats, top: int = 3,
            at_least_one: bool = False) -> list[list[dict]]:
    """Top contributing factors per row: the features furthest from the training population (robust z-score).

    Only notable deviations are listed. For a flagged transaction (``at_least_one``) the largest deviation is always
    reported, marked ``combined`` when no single feature is notable on its own (the combination is unusual).
    """
    z = stats.deviations(features)
    labels = {f.name: f.label for f in FEATURES}
    out: list[list[dict]] = []
    for idx in features.index:
        zr = z.loc[idx].sort_values(ascending=False)
        factors = []
        for rank, (name, deviation) in enumerate(zr.head(top).items()):
            combined = deviation < MIN_FACTOR_DEVIATION
            if combined and not (at_least_one and rank == 0):
                break
            factors.append({
                "feature": name,
                "label": labels[name],
                "description": describe(name, features.loc[idx], ctx.loc[idx]),
                "value": round(float(features.at[idx, name]), 4),
                "typical": round(stats.median[name], 4),
                "deviation": round(float(deviation), 2),
                "combined": bool(combined),
            })
        out.append(factors)
    return out
