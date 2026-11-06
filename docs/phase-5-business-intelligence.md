# Phase 5 – Business intelligence

## Analytics layer (migration V6)

A star schema of views in the `analytics` schema serves both Power BI and the in-app dashboard, so both report from
identical definitions.

| View | Contents |
|---|---|
| `dim_date` | Calendar 2020–2030 with year, quarter, month, ISO week, weekday, weekend flag |
| `dim_account`, `dim_merchant` | Account and merchant attributes |
| `dim_detector` | Audit rules AR-001…AR-006 plus the anomaly model (`ML`) |
| `fact_transactions` | Curated transactions (SAST business date and hour) with rule and model finding counts, flag, active model score |
| `fact_exceptions` | Potential exceptions with detector, severity (and rank), category, status, amount, model score, latest investigation |
| `fact_investigations` | Investigations with status, outcome, assignee, created/resolved dates, resolution and age hours, notes |
| `fact_pipeline_runs` | Pipeline runs with data-quality figures and valid-record rate |
| `fact_model_versions` | Model versions with precision, recall, F1, ROC AUC |
| `account_risk` | Per-account finding summary |
| `active_model_scores` | Helper: scores from the active model |

**Access.** The views run with their owner's privileges. The `analytics_reader` role (no login) can read only this
schema. When `BI_READER_PASSWORD` is set, the backend creates or updates a login (`BI_READER_USERNAME`, default
`auditiq_bi`) with that role, read-only transactions, `search_path = analytics` and a connection limit. An integration
test proves the login can read the views but is refused `users` and `audit_logs`.

## In-app Risk Analytics (Angular)

`GET /api/analytics/dashboard?from=&to=` (all roles) returns four sections for a transaction-date range (defaulting to
all data):

* **Executive overview:** transactions processed, total value, potential exceptions, high-priority findings, exception
  rate (flagged transactions ÷ transactions), pipeline health, daily volume, monthly flagged-rate trend.
* **Transaction analysis:** monthly volume, value or count by category, geographic distribution, channels, types.
* **Risk analysis:** exceptions by risk category, severity and detector; flagged transactions by rules only, model only
  or both; monthly rule and model findings (small multiples); top accounts requiring review.
* **Investigation monitoring:** open, in progress, resolved, average duration, oldest open, findings awaiting review;
  by status, outcome and assignee. This is current workload, so it isn't date-filtered.

Each fact view is scanned once using `GROUPING SETS`. The full year (111k transactions) returns in about 0.5 s.
Account-level detail (top accounts) is only included for auditors and audit managers; administrators see aggregates.

**Exports.** `GET /api/analytics/export/{dataset}` (auditors and audit managers) streams any analytics view as CSV.
Datasets are whitelisted, text that a spreadsheet could execute as a formula is neutralised, and each export is
audited (`ANALYTICS_EXPORTED`).

## Power BI

`powerbi/AuditIQ.pbip`: a generated Power BI project (TMDL semantic model and PBIR report) with 9 tables,
10 relationships, 20 DAX measures and 35 visuals on four pages (Executive Overview, Transaction Analysis, Risk
Analysis, Investigation Monitoring). See [powerbi/README.md](../powerbi/README.md) for opening, parameters,
credentials and the CSV alternative.

**Verification status.**
* Report JSON: validated against Microsoft's published schemas, all 45 files, with a negative test confirming that
  errors are detected.
* Model: loaded with Microsoft's Tabular Object Model (TMDL deserialisation).
* Field references: every visual field cross-checked against the model.
* The report has **not** yet been opened and refreshed in Power BI Desktop. Desktop on the build machine
  requires an organisational sign-in, which was not used. The first open in Desktop is the remaining check, for DAX
  evaluation and visual appearance.

## Tests

* Backend `DashboardIntegrationTest`: figures equal direct counts over the views; daily series sums to the total;
  administrators get no account detail; range validation; CSV export with header, quoting and formula neutralisation;
  unknown or forbidden datasets; auditing; reporting login can read `analytics` but not `public` and cannot write.
* Frontend `RiskAnalyticsComponent`: rendering, derived series, presets, export visibility by role, robustness to
  omitted null fields.
* CI regenerates the Power BI project and fails on drift.

## Fix found during verification

Spring omits null fields from JSON (`default-property-inclusion: non_null`), so the browser receives `undefined`, not
`null`. Several components compared API values with `=== null`. The Risk Analytics page failed to render (an
undefined duration reached `toFixed`), and the Investigations page would have done the same before any case was
resolved. All such checks now use `== null`, with regression tests.
