"""Audit-rule engine against a real PostgreSQL database with small, hand-built scenarios.

Each scenario uses its own accounts so the rules can be asserted precisely. Requires TEST_DATABASE_ADMIN_URL.
"""

from datetime import UTC, datetime, timedelta

import psycopg
import pytest

from app.rules.engine import LOCK_KEY, RuleEngine, RulesBusyError
from tests.integration.helpers import fetch

T0 = datetime(2026, 6, 10, 8, 0, tzinfo=UTC)  # 10:00 SAST
_seq = iter(range(1, 1_000_000))


@pytest.fixture(scope="module")
def seeded(database_url):
    with psycopg.connect(database_url, autocommit=True) as conn:
        accounts = [
            # id, type, status, last activity
            ("ACC-D00001", "CHEQUE", "ACTIVE", "2026-05-01"),   # duplicates
            ("ACC-L00001", "CHEQUE", "ACTIVE", "2026-05-01"),   # large
            ("ACC-S00001", "CHEQUE", "ACTIVE", "2026-05-01"),   # splitting
            ("ACC-S00002", "CHEQUE", "ACTIVE", "2026-05-01"),   # near-threshold but spread out
            ("ACC-H00001", "CREDIT", "ACTIVE", "2026-05-01"),   # high frequency
            ("ACC-R00001", "SAVINGS", "DORMANT", "2024-01-15"),  # dormant reactivation
            ("ACC-N00001", "CHEQUE", "ACTIVE", "2026-05-01"),   # normal control account
            ("ACC-V00001", "BUSINESS", "ACTIVE", "2026-05-01"),  # vendor payments
            ("ACC-V00002", "BUSINESS", "ACTIVE", "2026-05-01"),
        ]
        for acc, acc_type, status, last in accounts:
            conn.execute(
                """INSERT INTO accounts (account_id, customer_id, account_type, status, opened_date,
                                         last_activity_date) VALUES (%s, %s, %s, %s, '2015-01-01', %s)""",
                (acc, "CUS-" + acc[4:], acc_type, status, last))
        conn.execute("""INSERT INTO merchants (merchant_id, merchant_name, merchant_category, is_vendor) VALUES
                        ('MER-T0001', 'Test Grocer', 'GROCERIES', false),
                        ('MER-T0002', 'Test Advisory', 'PROFESSIONAL_SERVICES', true)""")

        def tx(acc, ts, amount, tx_type="PURCHASE", merchant="MER-T0001", status="COMPLETED", approval="NOT_REQUIRED",
               threshold=50_000, channel="POS"):
            conn.execute(
                """INSERT INTO transactions (transaction_id, account_id, customer_id, merchant_id, transaction_date,
                       transaction_amount, transaction_type, transaction_channel, account_type, transaction_status,
                       approval_threshold, approval_status)
                   SELECT %s, account_id, customer_id, %s, %s, %s, %s, %s, account_type, %s, %s, %s
                   FROM accounts WHERE account_id = %s""",
                (f"TXT{next(_seq):09d}", merchant, ts, amount, tx_type, channel, status, threshold, approval, acc))

        # Duplicates: same amount & business day (flag the later); a different day and a withdrawal are not flagged.
        tx("ACC-D00001", T0, 1_250.00)
        tx("ACC-D00001", T0 + timedelta(minutes=12), 1_250.00)
        tx("ACC-D00001", T0 + timedelta(days=1), 1_250.00)
        tx("ACC-D00001", T0, 500.00, "WITHDRAWAL", None, channel="ATM")
        tx("ACC-D00001", T0 + timedelta(minutes=30), 500.00, "WITHDRAWAL", None, channel="ATM")
        # Large: 2x threshold (HIGH); 1.1x without approval (CRITICAL); 1.1x approved (not flagged).
        tx("ACC-L00001", T0, 100_000.00, "TRANSFER", None, approval="APPROVED", channel="ONLINE")
        tx("ACC-L00001", T0 + timedelta(days=3), 55_000.00, "TRANSFER", None, approval="PENDING_APPROVAL",
           channel="ONLINE")
        tx("ACC-L00001", T0 + timedelta(days=5), 55_000.00, "TRANSFER", None, approval="APPROVED", channel="ONLINE")
        # Splitting: three near-threshold transfers within five hours; the control account spaces them by days.
        for h in (0, 2, 5):
            tx("ACC-S00001", T0 + timedelta(hours=h), 45_000.00 + h, "TRANSFER", None, channel="ONLINE")
        for d in (0, 3, 6):
            tx("ACC-S00002", T0 + timedelta(days=d), 45_000.00 + d, "TRANSFER", None, channel="ONLINE")
        # High frequency: 12 card payments in 33 minutes (one failed); a normal account with 3 payments that day.
        for i in range(12):
            tx("ACC-H00001", T0 + timedelta(minutes=3 * i), 150.00 + i, threshold=30_000,
               status="FAILED" if i == 4 else "COMPLETED")
        for i in range(3):
            tx("ACC-N00001", T0 + timedelta(hours=3 * i), 300.00 + i)
        # Dormant: last activity in 2024, two transactions now (reactivation + follow-up within the review period).
        tx("ACC-R00001", T0, 4_000.00, "WITHDRAWAL", None, channel="ATM")
        tx("ACC-R00001", T0 + timedelta(days=10), 2_000.00, "TRANSFER", None, channel="MOBILE")
        # Vendor: steady monthly payments of ~R20k from two accounts, then one payment of R90k (4.5x the median).
        for acc in ("ACC-V00001", "ACC-V00002"):
            for m in range(1, 6):
                tx(acc, datetime(2026, m, 15, 9, tzinfo=UTC), 20_000.00 + m, "VENDOR_PAYMENT", "MER-T0002",
                   threshold=250_000, channel="EFT")
        tx("ACC-V00001", datetime(2026, 6, 20, 9, tzinfo=UTC), 90_000.00, "VENDOR_PAYMENT", "MER-T0002",
           threshold=250_000, channel="EFT")
    return database_url


@pytest.fixture(scope="module")
def engine(seeded):
    return RuleEngine(lambda: psycopg.connect(seeded, autocommit=True))


@pytest.fixture(scope="module")
def first_run(engine):
    return engine.execute("tester")


def flagged(url, rule_id, account_id):
    return fetch(url, """SELECT t.transaction_amount, e.severity, e.reason, e.details
                         FROM audit_exceptions e JOIN transactions t USING (transaction_id)
                         WHERE e.rule_id = %s AND e.account_id = %s ORDER BY t.transaction_date""",
                 rule_id, account_id)


def test_all_enabled_rules_execute_and_are_recorded(first_run, seeded):
    results = {r["ruleId"]: r for r in first_run["results"]}
    assert set(results) == {"AR-001", "AR-002", "AR-003", "AR-004", "AR-005", "AR-006"}
    assert all(r["status"] == "SUCCESS" for r in results.values()), results
    rows = fetch(seeded, """SELECT rule_id, status, batch_ref, rule_version, matches_total, exceptions_found,
                                   thresholds_used, executed_by FROM rule_executions""")
    assert len(rows) == 6
    assert {r[2] for r in rows} == {first_run["batchRef"]}
    assert all(r[1] == "SUCCESS" and r[3] == 1 and r[4] == r[5] and r[6] and r[7] == "tester" for r in rows)
    last, = fetch(seeded, "SELECT count(*) FROM audit_rules WHERE last_executed_at IS NULL")
    assert last[0] == 0


def test_duplicate_payment_flags_only_the_later_payment_on_the_same_day(first_run, seeded):
    rows = flagged(seeded, "AR-001", "ACC-D00001")
    assert len(rows) == 1
    amount, severity, reason, details = rows[0]
    assert amount == 1250 and severity == "MEDIUM"
    assert details["minutesApart"] == 12 and details["groupSize"] == 2
    assert details["matchedTransactionId"] in reason


def test_large_transactions_escalate_missing_approval(first_run, seeded):
    rows = flagged(seeded, "AR-002", "ACC-L00001")
    assert [(r[0], r[1]) for r in rows] == [(100_000, "HIGH"), (55_000, "CRITICAL")]
    assert rows[1][3]["approvalMissing"] is True and "PENDING_APPROVAL" in rows[1][2]


def test_splitting_flags_clustered_near_threshold_payments_only(first_run, seeded):
    rows = flagged(seeded, "AR-003", "ACC-S00001")
    assert len(rows) == 3
    assert all(r[3]["transactionsInWindow"] == 3 and len(r[3]["relatedTransactionIds"]) == 3 for r in rows)
    assert flagged(seeded, "AR-003", "ACC-S00002") == []


def test_high_frequency_flags_every_transaction_in_the_burst(first_run, seeded):
    rows = flagged(seeded, "AR-004", "ACC-H00001")
    assert len(rows) == 12  # includes the failed attempt
    assert all(r[3]["peakCount"] == 12 and r[1] == "MEDIUM" for r in rows)
    assert flagged(seeded, "AR-004", "ACC-N00001") == []


def test_dormant_reactivation_and_follow_up_activity(first_run, seeded):
    rows = flagged(seeded, "AR-005", "ACC-R00001")
    assert len(rows) == 2
    assert rows[0][3]["reactivation"] is True and rows[0][3]["daysInactive"] > 365
    assert "First activity on this account" in rows[0][2]
    assert rows[1][3]["reactivation"] is False and "reactivation" in rows[1][2]


def test_vendor_payment_far_above_the_vendor_median(first_run, seeded):
    rows = flagged(seeded, "AR-006", "ACC-V00001")
    assert len(rows) == 1
    assert rows[0][0] == 90_000 and rows[0][3]["triggers"] == ["AMOUNT"]
    assert flagged(seeded, "AR-006", "ACC-V00002") == []


def test_no_rule_flags_the_normal_control_account(first_run, seeded):
    assert fetch(seeded, "SELECT count(*) FROM audit_exceptions WHERE account_id = 'ACC-N00001'")[0][0] == 0


def test_findings_are_neutral_and_carry_their_configuration(first_run, seeded):
    rows = fetch(seeded, "SELECT reason, details, detection_source, exception_ref FROM audit_exceptions")
    assert rows
    for reason, details, source, ref in rows:
        assert source == "RULE" and ref.startswith("EXC-")
        assert not any(word in reason.lower() for word in ("fraud", "criminal", "theft", "guilty"))
        assert details["ruleVersion"] == 1 and isinstance(details["thresholds"], dict)


def test_rerun_is_idempotent_and_preserves_exceptions(first_run, engine, seeded):
    before = fetch(seeded, "SELECT id, exception_ref FROM audit_exceptions ORDER BY id")
    second = engine.execute("tester")
    assert all(r["status"] == "SUCCESS" and r["exceptionsCreated"] == 0 for r in second["results"])
    first_matches = {r["ruleId"]: r["matchesTotal"] for r in first_run["results"]}
    assert {r["ruleId"]: r["matchesTotal"] for r in second["results"]} == first_matches
    assert fetch(seeded, "SELECT id, exception_ref FROM audit_exceptions ORDER BY id") == before


def test_thresholds_are_read_from_configuration(first_run, engine, seeded):
    with psycopg.connect(seeded, autocommit=True) as conn:
        conn.execute("""UPDATE audit_rules SET thresholds = jsonb_set(thresholds, '{min_transactions}', '20')
                        WHERE rule_id = 'AR-004'""")
    try:
        result = engine.execute("tester", ["AR-004"])["results"][0]
        assert result["status"] == "SUCCESS" and result["matchesTotal"] == 0
    finally:
        with psycopg.connect(seeded, autocommit=True) as conn:
            conn.execute("""UPDATE audit_rules SET thresholds = jsonb_set(thresholds, '{min_transactions}', '10')
                            WHERE rule_id = 'AR-004'""")


def test_invalid_configuration_fails_that_rule_only(first_run, engine, seeded):
    with psycopg.connect(seeded, autocommit=True) as conn:
        conn.execute("""UPDATE audit_rules SET thresholds = jsonb_set(thresholds, '{window_hours}', '-5')
                        WHERE rule_id = 'AR-003'""")
    try:
        results = {r["ruleId"]: r for r in engine.execute("tester", ["AR-002", "AR-003"])["results"]}
        assert results["AR-002"]["status"] == "SUCCESS"
        assert results["AR-003"]["status"] == "FAILED" and "window_hours" in results["AR-003"]["error"]
        (status, error), = fetch(seeded, "SELECT status, error_message FROM rule_executions WHERE id = %s",
                                 results["AR-003"]["executionId"])
        assert status == "FAILED" and "at least 1" in error
    finally:
        with psycopg.connect(seeded, autocommit=True) as conn:
            conn.execute("""UPDATE audit_rules SET thresholds = jsonb_set(thresholds, '{window_hours}', '24')
                            WHERE rule_id = 'AR-003'""")


def test_requests_are_validated(engine, seeded):
    with pytest.raises(ValueError, match="unknown rule"):
        engine.execute("tester", ["AR-999"])
    with pytest.raises(ValueError, match="invalid rule id"):
        engine.execute("tester", ["1; DROP TABLE x"])
    with psycopg.connect(seeded, autocommit=True) as conn:
        conn.execute("UPDATE audit_rules SET enabled = false WHERE rule_id = 'AR-001'")
    try:
        with pytest.raises(ValueError, match="disabled"):
            engine.execute("tester", ["AR-001"])
    finally:
        with psycopg.connect(seeded, autocommit=True) as conn:
            conn.execute("UPDATE audit_rules SET enabled = true WHERE rule_id = 'AR-001'")


def test_only_one_execution_at_a_time(engine, seeded):
    with psycopg.connect(seeded, autocommit=True) as holder:
        holder.execute("SELECT pg_advisory_lock(%s)", (LOCK_KEY,))
        with pytest.raises(RulesBusyError):
            engine.execute("tester")
    # The lock is released with the session; execution works again.
    assert engine.execute("tester", ["AR-001"])["results"][0]["status"] == "SUCCESS"
