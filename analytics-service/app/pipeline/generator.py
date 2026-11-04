"""Synthetic banking data generator.

Produces a reproducible (seeded) population of accounts and merchants, a year of realistic transactions, a controlled
set of injected audit anomalies with ground-truth labels, and a raw CSV feed that deliberately contains the kinds of
data-quality problems the ingestion pipeline must handle (missing values, inconsistent formats, invalid values and
duplicate records).

All identifiers and values are synthetic. No real customer data is used.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from app.pipeline import reference as ref

# Master data is generated from a fixed seed so every run sees the same account / merchant population.
REFERENCE_SEED = 20_240_501
N_CUSTOMERS = 6_000
N_ACCOUNTS = 8_000
N_MERCHANTS = 600

CATEGORY_MEDIAN_ZAR = {
    "GROCERIES": 650, "FUEL": 800, "RESTAURANTS": 350, "RETAIL": 900, "ELECTRONICS": 4_500, "TRAVEL": 6_000,
    "UTILITIES": 1_200, "TELECOMS": 600, "HEALTHCARE": 900, "ENTERTAINMENT": 300,
    "PROFESSIONAL_SERVICES": 28_000, "IT_SERVICES": 35_000, "OFFICE_SUPPLIES": 9_000, "CONSTRUCTION": 60_000,
    "LOGISTICS": 22_000,
}
# Relative frequency of consumer categories for card purchases.
CONSUMER_WEIGHTS = np.array([0.24, 0.14, 0.14, 0.14, 0.04, 0.03, 0.06, 0.05, 0.08, 0.08])
# Probability of a transaction occurring in each hour of the day (local time).
HOUR_WEIGHTS = np.array(
    [0.2, 0.1, 0.1, 0.1, 0.2, 0.5, 1.5, 3.5, 5.5, 6.5, 7.0, 7.0, 7.5, 7.0, 6.5, 6.5, 7.0, 7.5, 7.0, 5.5, 4.0, 2.5,
     1.2, 0.5]
)
HOUR_WEIGHTS = HOUR_WEIGHTS / HOUR_WEIGHTS.sum()

NAME_PREFIX = ("Blue", "Summit", "Karoo", "Protea", "Baobab", "Granite", "Atlas", "Coastal", "Highveld", "Ubuntu",
               "Silver", "Meridian", "Acacia", "Table", "Golden", "Cedar", "Pioneer", "Lowveld", "Zenith", "Indigo")
NAME_CORE = {
    "GROCERIES": ("Fresh Market", "Grocer", "Foods"), "FUEL": ("Fuel Stop", "Petroleum", "Garage"),
    "RESTAURANTS": ("Bistro", "Grill", "Kitchen"), "RETAIL": ("Outfitters", "Stores", "Boutique"),
    "ELECTRONICS": ("Electronics", "Digital", "Tech Hub"), "TRAVEL": ("Travel", "Air", "Getaways"),
    "UTILITIES": ("Power", "Water Services", "Municipal Utilities"), "TELECOMS": ("Mobile", "Telecom", "Connect"),
    "HEALTHCARE": ("Pharmacy", "Clinic", "Health"), "ENTERTAINMENT": ("Cinemas", "Entertainment", "Arcade"),
    "PROFESSIONAL_SERVICES": ("Advisory", "Consulting", "Legal Services"),
    "IT_SERVICES": ("Systems", "Software Solutions", "IT Services"),
    "OFFICE_SUPPLIES": ("Office Supplies", "Stationers", "Print & Supply"),
    "CONSTRUCTION": ("Construction", "Builders", "Civil Works"), "LOGISTICS": ("Logistics", "Freight", "Couriers"),
}
NAME_SUFFIX = ("(Pty) Ltd", "CC", "Holdings", "Group", "")


@dataclass
class GeneratedDataset:
    accounts: pd.DataFrame
    merchants: pd.DataFrame
    transactions: pd.DataFrame  # raw feed: every value a string, includes data-quality issues
    labels: pd.DataFrame  # transaction_id, anomaly_type
    stats: dict = field(default_factory=dict)

    def write_csv(self, directory: Path) -> Path:
        directory.mkdir(parents=True, exist_ok=True)
        self.accounts.to_csv(directory / "accounts.csv", index=False)
        self.merchants.to_csv(directory / "merchants.csv", index=False)
        self.transactions.to_csv(directory / "transactions.csv", index=False)
        self.labels.to_csv(directory / "labels.csv", index=False)
        return directory


# ---------------------------------------------------------------------------------------------------------------------
# Master data
# ---------------------------------------------------------------------------------------------------------------------
def generate_master_data(period_start: date) -> tuple[pd.DataFrame, pd.DataFrame]:
    rng = np.random.default_rng(REFERENCE_SEED)

    categories = list(ref.MERCHANT_CATEGORIES)
    # Roughly 80% consumer merchants, 20% B2B vendors.
    merchant_rows = []
    for i in range(1, N_MERCHANTS + 1):
        is_vendor = rng.random() < 0.2
        pool = ref.VENDOR_CATEGORIES if is_vendor else ref.CONSUMER_CATEGORIES
        category = pool[rng.integers(len(pool))] if is_vendor else categories[
            rng.choice(len(ref.CONSUMER_CATEGORIES), p=CONSUMER_WEIGHTS)]
        core = NAME_CORE[category][rng.integers(3)]
        suffix = NAME_SUFFIX[rng.integers(len(NAME_SUFFIX))]
        name = f"{NAME_PREFIX[rng.integers(len(NAME_PREFIX))]} {core} {suffix}".strip()
        merchant_rows.append({
            "merchant_id": f"MER-{i:05d}",
            "merchant_name": name,
            "merchant_category": category,
            "city": ref.CITIES[rng.integers(len(ref.CITIES))],
            "country": "ZA",
            "is_vendor": is_vendor,
        })
    merchants = pd.DataFrame(merchant_rows)

    account_rows = []
    type_p = [0.45, 0.25, 0.20, 0.10]
    for i in range(1, N_ACCOUNTS + 1):
        customer = int(rng.integers(1, N_CUSTOMERS + 1))
        acc_type = ref.ACCOUNT_TYPES[rng.choice(4, p=type_p)]
        roll = rng.random()
        status = "DORMANT" if roll < 0.06 else "CLOSED" if roll < 0.07 else "ACTIVE"
        opened = date(2012, 1, 1) + timedelta(days=int(rng.integers(0, 4_300)))
        if status == "DORMANT":
            last_activity = period_start - timedelta(days=int(rng.integers(400, 900)))
        else:
            last_activity = period_start - timedelta(days=int(rng.integers(1, 20)))
        last_activity = max(last_activity, opened)
        account_rows.append({
            "account_id": f"ACC-{i:06d}",
            "customer_id": f"CUS-{customer:06d}",
            "account_type": acc_type,
            "status": status,
            "branch": ref.CITIES[rng.integers(len(ref.CITIES))],
            "currency": "ZAR",
            "opened_date": opened.isoformat(),
            "last_activity_date": last_activity.isoformat(),
        })
    accounts = pd.DataFrame(account_rows)
    return accounts, merchants


# ---------------------------------------------------------------------------------------------------------------------
# Transactions
# ---------------------------------------------------------------------------------------------------------------------
class _Builder:
    """Accumulates clean transaction records (native types) and their anomaly labels."""

    def __init__(self, rng: np.random.Generator, accounts: pd.DataFrame, merchants: pd.DataFrame,
                 period_start: datetime, period_end: datetime):
        self.rng = rng
        self.period_start = period_start
        self.period_end = period_end
        self.records: list[dict] = []
        self.labels: dict[str, str] = {}
        # Unlabelled rows whose integrity an anomaly depends on (e.g. the original of a duplicate payment).
        self.protected: set[str] = set()
        self._ids: set[str] = set()

        self.acc: dict[str, dict] = accounts.set_index("account_id").to_dict("index")
        self.active = accounts[accounts.status == "ACTIVE"].account_id.to_numpy()
        self.dormant = accounts[accounts.status == "DORMANT"].account_id.to_numpy()
        self.business = accounts[(accounts.status == "ACTIVE") & (accounts.account_type == "BUSINESS")] \
            .account_id.to_numpy()
        self.consumer = accounts[(accounts.status == "ACTIVE") & (accounts.account_type != "BUSINESS")] \
            .account_id.to_numpy()

        self.merch_cat: dict[str, str] = dict(zip(merchants.merchant_id, merchants.merchant_category, strict=True))
        self.by_category = {c: merchants[merchants.merchant_category == c].merchant_id.to_numpy()
                            for c in ref.MERCHANT_CATEGORIES}
        self.vendors = merchants[merchants.is_vendor].merchant_id.to_numpy()

        # Each business account pays a small, stable set of vendors at a characteristic amount.
        self.vendor_book: dict[str, list[tuple[str, float]]] = {}
        for acc in self.business:
            chosen = rng.choice(self.vendors, size=int(rng.integers(3, 7)), replace=False)
            self.vendor_book[acc] = [
                (m, CATEGORY_MEDIAN_ZAR[self.merch_cat[m]] * float(rng.uniform(0.5, 1.6)))
                for m in chosen
            ]

    # -- helpers -------------------------------------------------------------------------------------------------
    def new_id(self) -> str:
        while True:
            tx_id = f"TXN{int(self.rng.integers(0, 16 ** 12)):012X}"
            if tx_id not in self._ids:
                self._ids.add(tx_id)
                return tx_id

    def lognormal(self, median: float, sigma: float = 0.6) -> float:
        return float(median * math.exp(sigma * self.rng.standard_normal()))

    def random_time(self, start: datetime | None = None, end: datetime | None = None) -> datetime:
        start = start or self.period_start
        end = end or self.period_end
        # Inclusive of the end date (end is normally 23:59:59 on the final day).
        days = max((end.date() - start.date()).days + 1, 1)
        day = start + timedelta(days=int(self.rng.integers(0, days)))
        hour = int(self.rng.choice(24, p=HOUR_WEIGHTS))
        return day.replace(hour=hour, minute=int(self.rng.integers(60)), second=int(self.rng.integers(60)))

    def add(self, account_id: str, ts: datetime, amount: float, tx_type: str, channel: str,
            merchant_id: str | None = None, status: str = "COMPLETED", approval: str | None = None,
            label: str | None = None, location: str | None = None) -> dict:
        acc = self.acc[account_id]
        threshold = float(ref.APPROVAL_THRESHOLDS[acc["account_type"]])
        amount = round(max(amount, 1.0), 2)
        if approval is None:
            if amount >= threshold:
                approval = "APPROVED" if self.rng.random() < 0.95 else "PENDING_APPROVAL"
            else:
                approval = "NOT_REQUIRED"
        if location is None:
            if channel == "ONLINE":
                location = ref.ONLINE_LOCATION
            elif self.rng.random() < 0.9:
                location = acc["branch"]
            else:
                location = ref.CITIES[self.rng.integers(len(ref.CITIES))]
        record = {
            "transaction_id": self.new_id(),
            "account_id": account_id,
            "customer_id": acc["customer_id"],
            "ts": ts,
            "amount": amount,
            "currency": "ZAR",
            "transaction_type": tx_type,
            "transaction_channel": channel,
            "merchant_id": merchant_id,
            "merchant_category": self.merch_cat[merchant_id] if merchant_id else None,
            "transaction_location": location,
            "account_type": acc["account_type"],
            "transaction_status": status,
            "approval_threshold": threshold,
            "approval_status": approval,
        }
        self.records.append(record)
        if label:
            self.labels[record["transaction_id"]] = label
        return record

    def random_status(self) -> str:
        r = self.rng.random()
        return "COMPLETED" if r < 0.96 else "PENDING" if r < 0.975 else "FAILED" if r < 0.99 else "REVERSED"

    def consumer_merchant(self, categories: tuple[str, ...] | None = None) -> str:
        if categories:
            category = categories[self.rng.integers(len(categories))]
        else:
            category = ref.CONSUMER_CATEGORIES[self.rng.choice(len(ref.CONSUMER_CATEGORIES), p=CONSUMER_WEIGHTS)]
        pool = self.by_category[category]
        return str(pool[self.rng.integers(len(pool))])

    def cap(self, account_id: str, amount: float) -> float:
        threshold = float(ref.APPROVAL_THRESHOLDS[self.acc[account_id]["account_type"]])
        # Ordinary activity rarely needs approval; a small share of legitimate large items remains.
        return amount if self.rng.random() < 0.003 else min(amount, threshold * float(self.rng.uniform(0.5, 0.95)))

    # -- normal activity -----------------------------------------------------------------------------------------
    def normal(self, n: int) -> None:
        activity = self.rng.gamma(shape=1.2, scale=1.0, size=len(self.active))
        picks = self.rng.choice(self.active, size=n, p=activity / activity.sum())
        for account_id in picks:
            acc_type = self.acc[account_id]["account_type"]
            ts = self.random_time()
            r = self.rng.random()
            if acc_type == "BUSINESS":
                if r < 0.45:
                    vendor, typical = self.vendor_book[account_id][self.rng.integers(len(self.vendor_book[account_id]))]
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(typical, 0.25)), "VENDOR_PAYMENT",
                             "EFT" if self.rng.random() < 0.7 else "ONLINE", vendor, self.random_status())
                elif r < 0.70:
                    m = self.consumer_merchant()
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(
                        CATEGORY_MEDIAN_ZAR[self.merch_cat[m]] * 2)), "PURCHASE",
                        "POS" if self.rng.random() < 0.6 else "ONLINE", m, self.random_status())
                elif r < 0.90:
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(25_000, 0.8)), "TRANSFER",
                             "ONLINE", None, self.random_status())
                else:
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(40_000, 0.7)), "DEPOSIT",
                             "BRANCH", None, self.random_status())
            elif acc_type == "SAVINGS":
                if r < 0.5:
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(3_000, 0.8)), "TRANSFER",
                             "MOBILE" if self.rng.random() < 0.6 else "ONLINE", None, self.random_status())
                elif r < 0.8:
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(4_000, 0.7)), "DEPOSIT",
                             "BRANCH" if self.rng.random() < 0.5 else "ATM", None, self.random_status())
                else:
                    self.add(account_id, ts, float(self.rng.integers(2, 40) * 100), "WITHDRAWAL", "ATM", None,
                             self.random_status())
            else:  # CHEQUE / CREDIT
                if r < 0.70:
                    m = self.consumer_merchant()
                    category = self.merch_cat[m]
                    channel = ("POS", "ONLINE", "MOBILE")[self.rng.choice(3, p=[0.55, 0.28, 0.17])]
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(CATEGORY_MEDIAN_ZAR[category])),
                             "PURCHASE", channel, m, self.random_status())
                elif r < 0.80:
                    self.add(account_id, ts, float(self.rng.integers(2, 30) * 100), "WITHDRAWAL", "ATM", None,
                             self.random_status())
                elif r < 0.90:
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(2_500, 0.9)), "TRANSFER",
                             ("MOBILE", "ONLINE", "BRANCH")[self.rng.choice(3, p=[0.5, 0.4, 0.1])], None,
                             self.random_status())
                elif r < 0.97:
                    m = self.consumer_merchant(("UTILITIES", "TELECOMS", "HEALTHCARE"))
                    self.add(account_id, ts, self.lognormal(CATEGORY_MEDIAN_ZAR[self.merch_cat[m]], 0.3),
                             "DEBIT_ORDER", "EFT", m, self.random_status())
                else:
                    self.add(account_id, ts, self.cap(account_id, self.lognormal(5_000, 0.7)), "DEPOSIT",
                             "BRANCH", None, self.random_status())

    # -- injected anomalies --------------------------------------------------------------------------------------
    def duplicate_payments(self, n: int) -> None:
        candidates = [r for r in self.records
                      if r["transaction_status"] == "COMPLETED" and r["transaction_id"] not in self.labels
                      and r["transaction_type"] in ("VENDOR_PAYMENT", "PURCHASE", "TRANSFER", "DEBIT_ORDER")]
        for idx in self.rng.choice(len(candidates), size=n, replace=False):
            original = candidates[idx]
            self.protected.add(original["transaction_id"])
            offset = timedelta(minutes=int(self.rng.integers(1, 46)))
            ts = original["ts"] + offset
            if ts.date() != original["ts"].date():
                ts = original["ts"] - offset
            self.add(original["account_id"], ts, original["amount"], original["transaction_type"],
                     original["transaction_channel"], original["merchant_id"], "COMPLETED",
                     original["approval_status"], "DUPLICATE_PAYMENT", original["transaction_location"])

    def large_transactions(self, n: int) -> None:
        for account_id in self.rng.choice(self.active, size=n, replace=False):
            acc_type = self.acc[account_id]["account_type"]
            threshold = float(ref.APPROVAL_THRESHOLDS[acc_type])
            amount = max(threshold * float(self.rng.uniform(1.5, 3.0)), self.lognormal(8_000) * self.rng.uniform(8, 40))
            r = self.rng.random()
            approval = "NOT_REQUIRED" if r < 0.3 else "APPROVED" if r < 0.8 else "PENDING_APPROVAL"
            if acc_type == "BUSINESS":
                vendor = self.vendor_book[account_id][0][0]
                self.add(account_id, self.random_time(), amount, "VENDOR_PAYMENT", "EFT", vendor, "COMPLETED",
                         approval, "LARGE_TRANSACTION")
            else:
                self.add(account_id, self.random_time(), amount, "TRANSFER",
                         ("ONLINE", "BRANCH", "MOBILE")[self.rng.integers(3)], None, "COMPLETED", approval,
                         "LARGE_TRANSACTION")

    def split_transactions(self, groups: int) -> None:
        for account_id in self.rng.choice(self.active, size=groups, replace=False):
            acc_type = self.acc[account_id]["account_type"]
            threshold = float(ref.APPROVAL_THRESHOLDS[acc_type])
            ts = self.random_time(end=self.period_end - timedelta(days=1))
            merchant = self.vendor_book[account_id][0][0] if acc_type == "BUSINESS" else None
            tx_type = "VENDOR_PAYMENT" if merchant else "TRANSFER"
            channel = "EFT" if merchant else "ONLINE"
            for _ in range(int(self.rng.integers(3, 6))):
                self.add(account_id, ts, threshold * float(self.rng.uniform(0.80, 0.99)), tx_type, channel, merchant,
                         "COMPLETED", "NOT_REQUIRED", "SPLIT_TRANSACTION")
                ts += timedelta(minutes=int(self.rng.integers(5, 61)))

    def high_frequency(self, bursts: int) -> None:
        for account_id in self.rng.choice(self.consumer, size=bursts, replace=False):
            ts = self.random_time(end=self.period_end - timedelta(days=1))
            channel = "ONLINE" if self.rng.random() < 0.7 else "POS"
            for _ in range(int(self.rng.integers(15, 31))):
                m = self.consumer_merchant(("ELECTRONICS", "RETAIL", "ENTERTAINMENT"))
                self.add(account_id, ts, self.lognormal(150, 0.7), "PURCHASE", channel, m,
                         "COMPLETED" if self.rng.random() < 0.85 else "FAILED", None, "HIGH_FREQUENCY")
                ts += timedelta(seconds=int(self.rng.integers(30, 240)))

    def dormant_activity(self, n_accounts: int) -> None:
        recent = self.period_end - timedelta(days=90)
        for account_id in self.rng.choice(self.dormant, size=min(n_accounts, len(self.dormant)), replace=False):
            for _ in range(int(self.rng.integers(1, 5))):
                if self.rng.random() < 0.5:
                    self.add(account_id, self.random_time(recent), float(self.rng.integers(10, 50) * 100),
                             "WITHDRAWAL", "ATM", None, "COMPLETED", None, "DORMANT_ACCOUNT_ACTIVITY")
                else:
                    self.add(account_id, self.random_time(recent), self.cap(account_id, self.lognormal(8_000, 0.6)),
                             "TRANSFER", "ONLINE" if self.rng.random() < 0.6 else "MOBILE", None, "COMPLETED", None,
                             "DORMANT_ACCOUNT_ACTIVITY")

    def unusual_vendor_payments(self, cases: int) -> None:
        for account_id in self.rng.choice(self.business, size=cases, replace=False):
            start = self.random_time(end=self.period_end - timedelta(days=15))
            if self.rng.random() < 0.6:
                vendor, typical = self.vendor_book[account_id][self.rng.integers(len(self.vendor_book[account_id]))]
                for _ in range(int(self.rng.integers(3, 7))):
                    ts = start + timedelta(days=int(self.rng.integers(0, 14)), minutes=int(self.rng.integers(0, 600)))
                    self.add(account_id, ts, typical * float(self.rng.uniform(4, 10)), "VENDOR_PAYMENT", "EFT",
                             vendor, "COMPLETED", None, "UNUSUAL_VENDOR_PAYMENT")
            else:
                known = {v for v, _ in self.vendor_book[account_id]}
                vendor = str(self.rng.choice([v for v in self.vendors if v not in known]))
                for _ in range(int(self.rng.integers(3, 6))):
                    ts = (start + timedelta(days=int(self.rng.integers(0, 10)))).replace(
                        hour=int(self.rng.integers(0, 5)), minute=int(self.rng.integers(60)))
                    self.add(account_id, ts, float(self.rng.uniform(80_000, 240_000)), "VENDOR_PAYMENT", "ONLINE",
                             vendor, "COMPLETED", None, "UNUSUAL_VENDOR_PAYMENT")


# ---------------------------------------------------------------------------------------------------------------------
# Raw feed rendering with data-quality issues
# ---------------------------------------------------------------------------------------------------------------------
def _render_raw(records: list[dict], labels: dict[str, str], protected: set[str], rng: np.random.Generator
                ) -> tuple[pd.DataFrame, dict]:
    df = pd.DataFrame(records).sort_values("ts", kind="stable").reset_index(drop=True)
    n = len(df)
    issues: dict[str, int] = {}

    # Foreign-currency online purchases are reported in the original currency.
    fx = {c: float(r) for c, r in ref.DEFAULT_FX_TO_ZAR.items()}
    keep_intact = (df.transaction_id.isin(labels) | df.transaction_id.isin(protected)).to_numpy()
    foreign = ((df.transaction_channel == "ONLINE") & (df.transaction_type == "PURCHASE")
               & (rng.random(n) < 0.03) & ~keep_intact)
    foreign_ccy = rng.choice(["USD", "EUR", "GBP"], size=n)
    df.loc[foreign, "currency"] = foreign_ccy[foreign]
    df.loc[foreign, "amount"] = [round(a / fx[c], 2) for a, c in zip(df.loc[foreign, "amount"], foreign_ccy[foreign],
                                                                      strict=True)]
    issues["foreign_currency"] = int(foreign.sum())

    out = pd.DataFrame({
        "transaction_id": df.transaction_id,
        "account_id": df.account_id,
        "customer_id": df.customer_id,
        "transaction_date": df.ts.dt.strftime("%Y-%m-%d %H:%M:%S"),
        "transaction_amount": df.amount.map(lambda a: f"{a:.2f}"),
        "currency": df.currency,
        "transaction_type": df.transaction_type,
        "transaction_channel": df.transaction_channel,
        "merchant_id": df.merchant_id.fillna(""),
        "merchant_category": df.merchant_category.fillna(""),
        "transaction_location": df.transaction_location,
        "account_type": df.account_type,
        "transaction_status": df.transaction_status,
        "approval_threshold": df.approval_threshold.map(lambda a: f"{a:.2f}"),
        "approval_status": df.approval_status,
    })

    def pick(fraction: float, mask: np.ndarray | None = None) -> np.ndarray:
        eligible = np.ones(n, dtype=bool) if mask is None else mask
        return eligible & (rng.random(n) < fraction)

    # --- cosmetic variations: must be fully standardised by the pipeline -----------------------------------------
    alt = pick(0.20)
    out.loc[alt, "transaction_date"] = df.ts[alt].dt.strftime("%d/%m/%Y %H:%M:%S")
    iso = pick(0.10) & ~alt
    out.loc[iso, "transaction_date"] = (df.ts[iso].dt.tz_localize(ref.LOCAL_TIMEZONE).dt.tz_convert("UTC")
                                        .dt.strftime("%Y-%m-%dT%H:%M:%SZ"))
    issues["date_format_variants"] = int(alt.sum() + iso.sum())

    case = pick(0.15, out.merchant_category.ne("").to_numpy())
    out.loc[case, "merchant_category"] = [
        (c.lower() if rng.random() < 0.5 else c.title().replace("_", " ")) + (" " if rng.random() < 0.3 else "")
        for c in out.loc[case, "merchant_category"]]
    issues["category_variants"] = int(case.sum())

    inverse_channel: dict[str, list[str]] = {}
    for k, v in ref.CHANNEL_SYNONYMS.items():
        inverse_channel.setdefault(v, []).append(k.title())
    syn = pick(0.10, out.transaction_channel.isin(list(inverse_channel)).to_numpy())
    out.loc[syn, "transaction_channel"] = [
        inverse_channel[c][rng.integers(len(inverse_channel[c]))] for c in out.loc[syn, "transaction_channel"]]
    issues["channel_synonyms"] = int(syn.sum())

    tsyn = pick(0.05, out.transaction_type.isin(["VENDOR_PAYMENT", "DEBIT_ORDER"]).to_numpy())
    out.loc[tsyn, "transaction_type"] = out.loc[tsyn, "transaction_type"].str.replace("_", " ").str.title()
    issues["type_synonyms"] = int(tsyn.sum())

    lower = pick(0.05)
    out.loc[lower, "currency"] = out.loc[lower, "currency"].str.lower()
    blank_ccy = pick(0.03, (out.currency == "ZAR").to_numpy())
    out.loc[blank_ccy, "currency"] = ""
    issues["currency_variants"] = int(lower.sum() + blank_ccy.sum())

    thousands = pick(0.05)
    out.loc[thousands, "transaction_amount"] = [f"{float(a):,.2f}" for a in out.loc[thousands, "transaction_amount"]]
    issues["thousand_separators"] = int(thousands.sum())

    pad = pick(0.05)
    out.loc[pad, "account_id"] = " " + out.loc[pad, "account_id"] + " "
    issues["whitespace_padding"] = int(pad.sum())

    missing_category = pick(0.02, out.merchant_id.ne("").to_numpy())
    out.loc[missing_category, "merchant_category"] = ""
    issues["missing_category_enrichable"] = int(missing_category.sum())

    # --- destructive issues: never on anomaly rows (or rows they depend on) so ground truth survives -------------
    clean = ~keep_intact
    taken = np.zeros(n, dtype=bool)

    def corrupt(name: str, fraction: float, column: str, value, mask: np.ndarray | None = None) -> None:
        nonlocal taken
        eligible = clean & ~taken & (np.ones(n, dtype=bool) if mask is None else mask)
        chosen = eligible & (rng.random(n) < fraction)
        out.loc[chosen, column] = value(chosen.sum()) if callable(value) else value
        taken |= chosen
        issues[name] = int(chosen.sum())

    corrupt("missing_account_id", 0.0015, "account_id", "")
    corrupt("missing_customer_id", 0.0005, "customer_id", "")
    corrupt("missing_amount", 0.0015, "transaction_amount", "")
    corrupt("missing_date", 0.001, "transaction_date", "")
    corrupt("missing_channel", 0.0015, "transaction_channel", "")
    corrupt("non_numeric_amount", 0.0005, "transaction_amount", "N/A")
    corrupt("negative_amount", 0.0005, "transaction_amount", lambda k: [f"-{v:.2f}" for v in rng.uniform(10, 900, k)])
    corrupt("invalid_date", 0.0005, "transaction_date", "2026-02-30 10:15:00")
    corrupt("future_date", 0.0002, "transaction_date", "2031-01-01 09:00:00")
    corrupt("unknown_account", 0.0005, "account_id", "ACC-999999")
    corrupt("unknown_merchant", 0.0003, "merchant_id", "MER-99999", out.merchant_id.ne("").to_numpy())
    corrupt("invalid_status", 0.0003, "transaction_status", "UNKNOWN")

    # --- exact duplicate records (same transaction_id re-sent by the source system) -------------------------------
    dup_rows = out[clean & (rng.random(n) < 0.004)]
    issues["duplicate_records"] = len(dup_rows)
    out = pd.concat([out, dup_rows]).sort_index(kind="stable").reset_index(drop=True)
    return out, issues


def generate_dataset(seed: int = 42, n_transactions: int = 110_000, end_date: date = date(2026, 10, 31)
                     ) -> GeneratedDataset:
    """Generate a full synthetic dataset. Anomaly volumes scale with ``n_transactions``."""
    if n_transactions < 1_000:
        raise ValueError("n_transactions must be at least 1,000")
    period_end = datetime.combine(end_date, datetime.max.time()).replace(microsecond=0)
    period_start = datetime.combine(end_date - timedelta(days=364), datetime.min.time())
    accounts, merchants = generate_master_data(period_start.date())

    rng = np.random.default_rng(seed)
    builder = _Builder(rng, accounts, merchants, period_start, period_end)
    scale = n_transactions / 110_000

    def scaled(base: int) -> int:
        return max(3, round(base * scale))

    builder.normal(n_transactions)
    builder.duplicate_payments(scaled(250))
    builder.large_transactions(scaled(150))
    builder.split_transactions(scaled(120))
    builder.high_frequency(scaled(35))
    builder.dormant_activity(scaled(60))
    builder.unusual_vendor_payments(scaled(40))

    raw, issues = _render_raw(builder.records, builder.labels, builder.protected, rng)
    labels = pd.DataFrame(sorted(builder.labels.items()), columns=["transaction_id", "anomaly_type"])
    stats = {
        "seed": seed,
        "clean_transactions": len(builder.records),
        "raw_rows": len(raw),
        "injected_anomalies": labels.anomaly_type.value_counts().to_dict(),
        "data_quality_issues": issues,
        "period_start": period_start.date().isoformat(),
        "period_end": end_date.isoformat(),
    }
    return GeneratedDataset(accounts, merchants, raw, labels, stats)
