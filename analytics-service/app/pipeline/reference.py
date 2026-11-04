"""Controlled vocabularies, synonym maps and business parameters shared by the generator and the validator.

Everything that a business user might want to tune lives here rather than inside the processing code.
"""

from decimal import Decimal

ACCOUNT_TYPES = ("CHEQUE", "SAVINGS", "CREDIT", "BUSINESS")

# Single-transaction amount at or above which approval is required (ZAR).
APPROVAL_THRESHOLDS: dict[str, Decimal] = {
    "CHEQUE": Decimal("50000.00"),
    "SAVINGS": Decimal("50000.00"),
    "CREDIT": Decimal("30000.00"),
    "BUSINESS": Decimal("250000.00"),
}

TRANSACTION_TYPES = ("PURCHASE", "TRANSFER", "WITHDRAWAL", "DEPOSIT", "VENDOR_PAYMENT", "DEBIT_ORDER")
CHANNELS = ("POS", "ONLINE", "MOBILE", "BRANCH", "ATM", "EFT")
STATUSES = ("COMPLETED", "PENDING", "FAILED", "REVERSED")
APPROVAL_STATUSES = ("NOT_REQUIRED", "APPROVED", "PENDING_APPROVAL", "REJECTED")
CURRENCIES = ("ZAR", "USD", "EUR", "GBP")

# Types that legitimately have no merchant.
MERCHANTLESS_TYPES = frozenset({"TRANSFER", "WITHDRAWAL", "DEPOSIT"})

CONSUMER_CATEGORIES = (
    "GROCERIES", "FUEL", "RESTAURANTS", "RETAIL", "ELECTRONICS", "TRAVEL",
    "UTILITIES", "TELECOMS", "HEALTHCARE", "ENTERTAINMENT",
)
VENDOR_CATEGORIES = ("PROFESSIONAL_SERVICES", "IT_SERVICES", "OFFICE_SUPPLIES", "CONSTRUCTION", "LOGISTICS")
MERCHANT_CATEGORIES = CONSUMER_CATEGORIES + VENDOR_CATEGORIES

CITIES = (
    "Johannesburg", "Cape Town", "Durban", "Pretoria", "Gqeberha", "Bloemfontein",
    "East London", "Polokwane", "Mbombela", "Kimberley", "Rustenburg", "Pietermaritzburg",
)
ONLINE_LOCATION = "ONLINE"

# Raw-feed spellings mapped to the controlled vocabulary (keys are upper-cased and whitespace-normalised).
CHANNEL_SYNONYMS: dict[str, str] = {
    "MOBILE APP": "MOBILE",
    "APP": "MOBILE",
    "INTERNET BANKING": "ONLINE",
    "WEB": "ONLINE",
    "POINT OF SALE": "POS",
    "CARD": "POS",
    "BRANCH COUNTER": "BRANCH",
    "AUTOMATED TELLER": "ATM",
    "ELECTRONIC FUNDS TRANSFER": "EFT",
}
TYPE_SYNONYMS: dict[str, str] = {
    "VENDOR PAYMENT": "VENDOR_PAYMENT",
    "SUPPLIER PAYMENT": "VENDOR_PAYMENT",
    "DEBIT ORDER": "DEBIT_ORDER",
    "CASH WITHDRAWAL": "WITHDRAWAL",
}
STATUS_SYNONYMS: dict[str, str] = {"SUCCESS": "COMPLETED", "SETTLED": "COMPLETED", "DECLINED": "FAILED"}

# Fallback reference FX rates; the pipeline prefers the values in system_settings (fx.<CCY>_ZAR).
DEFAULT_FX_TO_ZAR: dict[str, Decimal] = {
    "ZAR": Decimal("1"),
    "USD": Decimal("18.20"),
    "EUR": Decimal("19.70"),
    "GBP": Decimal("23.10"),
}

LOCAL_TIMEZONE = "Africa/Johannesburg"

# Upper bound for a single plausible transaction; larger values are treated as data errors.
MAX_PLAUSIBLE_AMOUNT = Decimal("100000000.00")

REQUIRED_FIELDS = (
    "transaction_id", "account_id", "customer_id", "transaction_date", "transaction_amount",
    "transaction_type", "transaction_channel", "account_type", "transaction_status",
)

RAW_COLUMNS = (
    "transaction_id", "account_id", "customer_id", "transaction_date", "transaction_amount", "currency",
    "transaction_type", "transaction_channel", "merchant_id", "merchant_category", "transaction_location",
    "account_type", "transaction_status", "approval_threshold", "approval_status",
)

ANOMALY_TYPES = (
    "DUPLICATE_PAYMENT", "LARGE_TRANSACTION", "SPLIT_TRANSACTION", "HIGH_FREQUENCY",
    "DORMANT_ACCOUNT_ACTIVITY", "UNUSUAL_VENDOR_PAYMENT",
)
