"""Detection SQL for the audit rules.

Each rule is a single set-based query over the curated ``transactions`` table that returns one row per flagged
transaction with the columns ``transaction_id, account_id, severity, reason, details``:

* ``severity`` – NULL to use the rule's configured severity, or an escalated value for that finding;
* ``reason`` – a factual, neutral explanation of why the transaction met the rule (never an accusation);
* ``details`` – JSON evidence (matched transactions, measured values, thresholds applied) for the investigator.

Business dates and hours are evaluated in the bank's local time zone (SAST). All thresholds are bound parameters
taken from the rule's validated configuration (``%(name)s``); nothing user-supplied is interpolated into SQL.
"""

from __future__ import annotations

from dataclasses import dataclass

TZ = "'Africa/Johannesburg'"


def money(expr: str) -> str:
    return f"'R ' || to_char({expr}, 'FM999,999,999,990.00')"


@dataclass(frozen=True)
class RuleQuery:
    rule_id: str
    sql: str


DUPLICATE_PAYMENTS = RuleQuery("AR-001", f"""
WITH base AS (
    SELECT t.transaction_id, t.account_id, t.transaction_amount, t.transaction_date, t.merchant_id,
           (t.transaction_date AT TIME ZONE {TZ})::date AS business_date,
           CASE WHEN %(match_merchant)s THEN coalesce(t.merchant_id, '-') ELSE '-' END AS merchant_key
    FROM transactions t
    WHERE t.transaction_status = ANY(%(statuses)s)
      AND t.transaction_type = ANY(%(transaction_types)s)
      AND t.transaction_amount >= %(min_amount)s
), grouped AS (
    SELECT b.*,
           row_number() OVER w AS position,
           count(*) OVER (PARTITION BY account_id, transaction_amount, business_date, merchant_key) AS group_size,
           first_value(transaction_id) OVER w AS first_id,
           first_value(transaction_date) OVER w AS first_at
    FROM base b
    WINDOW w AS (PARTITION BY account_id, transaction_amount, business_date, merchant_key
                 ORDER BY transaction_date, transaction_id)
)
SELECT transaction_id, account_id,
       CASE WHEN transaction_amount >= %(high_value_amount)s THEN 'HIGH' END AS severity,
       'Same account, amount (' || {money('transaction_amount')} || ') and business date ('
           || to_char(business_date, 'YYYY-MM-DD') || ') as transaction ' || first_id || ', recorded '
           || round(extract(epoch FROM transaction_date - first_at) / 60)::int || ' minute(s) later. '
           || group_size || ' matching transactions on this date.' AS reason,
       jsonb_build_object('matchedTransactionId', first_id,
                          'minutesApart', round(extract(epoch FROM transaction_date - first_at) / 60)::int,
                          'groupSize', group_size, 'businessDate', business_date,
                          'amount', transaction_amount, 'relatedTransactionIds', jsonb_build_array(first_id))
           AS details
FROM grouped
WHERE position > 1
""")

LARGE_TRANSACTIONS = RuleQuery("AR-002", f"""
SELECT t.transaction_id, t.account_id,
       CASE WHEN %(flag_unapproved)s AND t.transaction_amount >= t.approval_threshold
                 AND t.approval_status <> 'APPROVED' THEN 'CRITICAL' END AS severity,
       'Amount ' || {money('t.transaction_amount')} || ' is '
           || round(t.transaction_amount / t.approval_threshold, 1) || ' times the '
           || {money('t.approval_threshold')} || ' approval threshold for ' || t.account_type || ' accounts'
           || CASE WHEN t.transaction_amount >= t.approval_threshold AND t.approval_status <> 'APPROVED'
                   THEN '; approval status is ' || t.approval_status
                        || ', although approval is required at or above the threshold'
                   ELSE '; approval status is ' || t.approval_status END
           || '.' AS reason,
       jsonb_build_object('amount', t.transaction_amount, 'approvalThreshold', t.approval_threshold,
                          'thresholdMultiple', round(t.transaction_amount / t.approval_threshold, 2),
                          'approvalStatus', t.approval_status, 'accountType', t.account_type,
                          'approvalMissing', t.transaction_amount >= t.approval_threshold
                                             AND t.approval_status <> 'APPROVED') AS details
FROM transactions t
WHERE t.transaction_status = ANY(%(statuses)s)
  AND t.approval_threshold > 0
  AND (t.transaction_amount >= t.approval_threshold * %(threshold_multiple)s
       OR (%(flag_unapproved)s AND t.transaction_amount >= t.approval_threshold
           AND t.approval_status <> 'APPROVED'))
""")

TRANSACTION_SPLITTING = RuleQuery("AR-003", f"""
WITH base AS (
    SELECT t.transaction_id, t.account_id, t.transaction_date, t.transaction_amount, t.approval_threshold
    FROM transactions t
    WHERE t.transaction_status = ANY(%(statuses)s)
      AND t.transaction_type = ANY(%(transaction_types)s)
      AND t.approval_threshold > 0
      AND t.transaction_amount < t.approval_threshold
      AND t.transaction_amount >= t.approval_threshold * %(near_threshold_ratio)s
), windowed AS (
    SELECT b.*,
           count(*) OVER w AS cnt,
           sum(transaction_amount) OVER w AS total,
           jsonb_agg(transaction_id) OVER w AS related
    FROM base b
    WINDOW w AS (PARTITION BY account_id ORDER BY transaction_date
                 RANGE BETWEEN make_interval(hours => %(window_hours)s) PRECEDING
                           AND make_interval(hours => %(window_hours)s) FOLLOWING)
)
SELECT transaction_id, account_id, NULL AS severity,
       cnt || ' transactions between ' || round(%(near_threshold_ratio)s * 100)::int
           || ' and 100 percent of the ' || {money('approval_threshold')}
           || ' approval threshold within ' || %(window_hours)s || ' hours of each other on this account, totalling '
           || {money('total')} || '. Each is individually below the threshold; together they exceed it.' AS reason,
       jsonb_build_object('transactionsInWindow', cnt, 'windowTotal', total, 'approvalThreshold', approval_threshold,
                          'windowHours', %(window_hours)s, 'relatedTransactionIds', related) AS details
FROM windowed
WHERE cnt >= %(min_transactions)s AND total > approval_threshold
""")

HIGH_FREQUENCY = RuleQuery("AR-004", f"""
WITH base AS (
    SELECT t.transaction_id, t.account_id, t.transaction_date
    FROM transactions t
    WHERE t.transaction_status = ANY(%(statuses)s)
), trailing_counts AS (
    SELECT b.*,
           count(*) OVER (PARTITION BY account_id ORDER BY transaction_date
                          RANGE BETWEEN make_interval(mins => %(window_minutes)s) PRECEDING AND CURRENT ROW)
               AS trailing_count
    FROM base b
), peaks AS (
    -- A transaction belongs to a burst when any window that contains it reaches the limit.
    SELECT t.*,
           max(trailing_count) OVER (PARTITION BY account_id ORDER BY transaction_date
                                     RANGE BETWEEN CURRENT ROW AND make_interval(mins => %(window_minutes)s) FOLLOWING)
               AS peak_count,
           min(transaction_date) OVER (PARTITION BY account_id ORDER BY transaction_date
                                       RANGE BETWEEN make_interval(mins => %(window_minutes)s) PRECEDING
                                                 AND make_interval(mins => %(window_minutes)s) FOLLOWING)
               AS burst_from,
           max(transaction_date) OVER (PARTITION BY account_id ORDER BY transaction_date
                                       RANGE BETWEEN make_interval(mins => %(window_minutes)s) PRECEDING
                                                 AND make_interval(mins => %(window_minutes)s) FOLLOWING)
               AS burst_to
    FROM trailing_counts t
)
SELECT transaction_id, account_id,
       CASE WHEN peak_count >= 2 * %(min_transactions)s THEN 'HIGH' END AS severity,
       'Part of a burst of up to ' || peak_count || ' transactions on this account within ' || %(window_minutes)s
           || ' minutes (rule limit: ' || %(min_transactions)s || '), between '
           || to_char(burst_from AT TIME ZONE {TZ}, 'YYYY-MM-DD HH24:MI') || ' and '
           || to_char(burst_to AT TIME ZONE {TZ}, 'HH24:MI') || ' SAST.' AS reason,
       jsonb_build_object('peakCount', peak_count, 'windowMinutes', %(window_minutes)s,
                          'burstFrom', burst_from, 'burstTo', burst_to) AS details
FROM peaks
WHERE peak_count >= %(min_transactions)s
""")

DORMANT_ACCOUNT_ACTIVITY = RuleQuery("AR-005", f"""
WITH ordered AS (
    SELECT t.transaction_id, t.account_id, t.transaction_date,
           lag(t.transaction_date) OVER (PARTITION BY t.account_id ORDER BY t.transaction_date, t.transaction_id)
               AS previous_at
    FROM transactions t
    WHERE t.transaction_status = ANY(%(statuses)s)
), gaps AS (
    SELECT o.*, a.status AS account_status, a.last_activity_date,
           coalesce(o.previous_at, (a.last_activity_date::timestamp AT TIME ZONE {TZ})) AS prior_activity
    FROM ordered o JOIN accounts a ON a.account_id = o.account_id
), flagged AS (
    SELECT g.*,
           (g.prior_activity IS NOT NULL
            AND g.transaction_date - g.prior_activity >= make_interval(days => %(dormancy_days)s)) AS reactivation
    FROM gaps g
), windowed AS (
    SELECT f.*,
           max(CASE WHEN reactivation THEN transaction_date END)
               OVER (PARTITION BY account_id ORDER BY transaction_date, transaction_id
                     ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS reactivated_at,
           max(CASE WHEN reactivation THEN prior_activity END)
               OVER (PARTITION BY account_id ORDER BY transaction_date, transaction_id
                     ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS dormant_since
    FROM flagged f
)
SELECT transaction_id, account_id, NULL AS severity,
       CASE
           WHEN reactivation THEN
               'First activity on this account in '
               || extract(day FROM transaction_date - prior_activity)::int || ' days (previous activity '
               || to_char(prior_activity AT TIME ZONE {TZ}, 'YYYY-MM-DD') || '); the dormancy period is '
               || %(dormancy_days)s || ' days.'
           WHEN reactivated_at IS NOT NULL
                AND transaction_date - reactivated_at <= make_interval(days => %(review_days)s) THEN
               'Activity within ' || %(review_days)s || ' days of the account''s reactivation on '
               || to_char(reactivated_at AT TIME ZONE {TZ}, 'YYYY-MM-DD') || ', after inactivity since '
               || to_char(dormant_since AT TIME ZONE {TZ}, 'YYYY-MM-DD') || '.'
           ELSE 'Activity on an account recorded as DORMANT in the account master.'
       END
       || CASE WHEN account_status = 'DORMANT' AND (reactivation OR reactivated_at IS NOT NULL)
               THEN ' The account is recorded as DORMANT in the account master.' ELSE '' END AS reason,
       jsonb_build_object('accountStatus', account_status, 'lastActivityDate', last_activity_date,
                          'priorActivityAt', prior_activity, 'reactivation', reactivation,
                          'reactivatedAt', reactivated_at, 'dormancyDays', %(dormancy_days)s,
                          'daysInactive', CASE WHEN reactivation
                                               THEN extract(day FROM transaction_date - prior_activity)::int END)
           AS details
FROM windowed
WHERE reactivation
   OR (reactivated_at IS NOT NULL AND transaction_date - reactivated_at <= make_interval(days => %(review_days)s))
   OR (%(include_dormant_status)s AND account_status = 'DORMANT')
""")

UNUSUAL_VENDOR_PAYMENTS = RuleQuery("AR-006", f"""
WITH vp AS (
    SELECT t.transaction_id, t.account_id, t.merchant_id, m.merchant_name, t.transaction_date, t.transaction_amount,
           extract(hour FROM t.transaction_date AT TIME ZONE {TZ})::int AS local_hour
    FROM transactions t JOIN merchants m ON m.merchant_id = t.merchant_id AND m.is_vendor
    WHERE t.transaction_type = 'VENDOR_PAYMENT' AND t.transaction_status = ANY(%(statuses)s)
), span AS (
    SELECT greatest(extract(epoch FROM max(transaction_date) - min(transaction_date)) / 86400, 1) AS days FROM vp
), vendor_median AS (
    SELECT merchant_id, percentile_cont(0.5) WITHIN GROUP (ORDER BY transaction_amount) AS median_amount,
           count(*) AS vendor_payments
    FROM vp GROUP BY merchant_id
), pair AS (
    SELECT v.*,
           count(*) OVER (PARTITION BY account_id, merchant_id) AS pair_total,
           count(*) OVER (PARTITION BY account_id, merchant_id ORDER BY transaction_date
                          RANGE BETWEEN make_interval(days => %(frequency_window_days)s) PRECEDING AND CURRENT ROW)
               AS trailing_count,
           min(transaction_date) OVER (PARTITION BY account_id, merchant_id) AS first_paid_at
    FROM vp v
), measured AS (
    SELECT p.*, vm.median_amount, vm.vendor_payments,
           max(p.trailing_count) OVER (PARTITION BY p.account_id, p.merchant_id ORDER BY p.transaction_date
                                       RANGE BETWEEN CURRENT ROW
                                               AND make_interval(days => %(frequency_window_days)s) FOLLOWING)
               AS window_count,
           s.days AS span_days
    FROM pair p JOIN vendor_median vm ON vm.merchant_id = p.merchant_id CROSS JOIN span s
), rated AS (
    -- Usual rate: the account's other payments to this vendor, spread over the observed period.
    SELECT m.*, greatest(pair_total - window_count, 0) * %(frequency_window_days)s / span_days AS usual_rate
    FROM measured m
), tests AS (
    SELECT m.*,
           (median_amount > 0 AND transaction_amount >= median_amount * %(amount_multiple)s) AS amount_test,
           (window_count >= %(frequency_min_payments)s
            AND window_count >= %(frequency_multiple)s * usual_rate) AS frequency_test,
           (transaction_amount >= %(new_vendor_min_amount)s
            AND local_hour >= %(off_hours_start)s AND local_hour < %(off_hours_end)s
            AND transaction_date - first_paid_at <= make_interval(days => %(new_vendor_days)s)) AS new_vendor_test
    FROM rated m
)
SELECT transaction_id, account_id,
       CASE WHEN amount_test::int + frequency_test::int + new_vendor_test::int > 1 THEN 'HIGH' END AS severity,
       'Payment of ' || {money('transaction_amount')} || ' to vendor ' || merchant_id || ' (' || merchant_name || '): '
           || concat_ws('; ',
                CASE WHEN amount_test THEN round((transaction_amount / median_amount)::numeric, 1)
                     || ' times the vendor''s median payment of ' || {money('median_amount::numeric')} END,
                CASE WHEN frequency_test THEN window_count || ' payments to this vendor within '
                     || %(frequency_window_days)s || ' days, against a usual rate of '
                     || round(usual_rate::numeric, 1) || ' per ' || %(frequency_window_days)s || ' days' END,
                CASE WHEN new_vendor_test THEN 'made at ' || lpad(local_hour::text, 2, '0')
                     || ':00 SAST, outside business hours, within ' || %(new_vendor_days)s
                     || ' days of the first payment from this account to the vendor' END)
           || '.' AS reason,
       jsonb_build_object('merchantId', merchant_id, 'merchantName', merchant_name,
                          'vendorMedianAmount', round(median_amount::numeric, 2),
                          'amountMultiple', round((transaction_amount / nullif(median_amount, 0))::numeric, 2),
                          'paymentsInWindow', window_count, 'usualRatePerWindow', round(usual_rate::numeric, 2),
                          'localHour', local_hour, 'firstPaidAt', first_paid_at,
                          'triggers', to_jsonb(array_remove(ARRAY[
                              CASE WHEN amount_test THEN 'AMOUNT' END,
                              CASE WHEN frequency_test THEN 'FREQUENCY' END,
                              CASE WHEN new_vendor_test THEN 'NEW_VENDOR_OFF_HOURS' END], NULL))) AS details
FROM tests
WHERE amount_test OR frequency_test OR new_vendor_test
""")

RULE_QUERIES: dict[str, RuleQuery] = {q.rule_id: q for q in (
    DUPLICATE_PAYMENTS, LARGE_TRANSACTIONS, TRANSACTION_SPLITTING, HIGH_FREQUENCY, DORMANT_ACCOUNT_ACTIVITY,
    UNUSUAL_VENDOR_PAYMENTS,
)}
