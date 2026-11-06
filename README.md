# AuditIQ – AI-Powered Banking Audit & Risk Intelligence Platform

AuditIQ helps internal audit teams turn transaction data into **evidence-based audit intelligence**: automated data
preparation, SQL audit rules, Isolation Forest anomaly detection, Power BI / Angular dashboards, and a RAG assistant +
controlled investigation agent – with human review of every outcome.

> **Synthetic data only.** Findings are *potential audit exceptions* for human review, never determinations of fraud.

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Foundation: schema, Docker, Spring Boot API, auth/RBAC, audit trail, Angular shell | ✅ Complete |
| 2 | Synthetic data generator, ingestion pipeline, data quality, transactions | ✅ Complete |
| 3 | SQL audit rules, exceptions, investigation management | ✅ Complete |
| 4 | Isolation Forest model & evaluation | ✅ Complete |
| 5 | Angular analytics + Power BI | ✅ Complete |
| 6 | RAG assistant | ⏳ Next |
| 7 | AI investigation agent | — |
| 8 | Hardening, docs, deployment | — |

## Architecture

```
Angular SPA ──► nginx ──► Spring Boot API (JWT, RBAC, audit log) ──► PostgreSQL 16 + pgvector
                                   │                                      ▲
                                   └──► FastAPI analytics/AI service ─────┘ ──► LLM provider
```

* [System architecture](docs/architecture.md)
* [Database ERD](docs/erd.md)
* [Phase 1 plan & decisions](docs/phase-1-plan.md)
* [Phase 2 data engineering](docs/phase-2-data-engineering.md)
* [Phase 3 audit analytics & investigations](docs/phase-3-audit-analytics.md)
* [Phase 4 anomaly model](docs/phase-4-anomaly-model.md)
* [Phase 5 business intelligence](docs/phase-5-business-intelligence.md) · [Power BI report](powerbi/README.md)
* API documentation: Swagger UI at `http://localhost:4200/swagger-ui.html` once running

## Repository layout

```
backend/            Spring Boot 3.5 / Java 21 – REST API, security, Flyway migrations
frontend/           Angular 20 + Angular Material – SPA served by nginx
analytics-service/  Python 3.12 FastAPI – pipeline, rules engine, ML, RAG, agent
database/init/      One-time PostgreSQL extension setup
docs/               Architecture, ERD, phase plans
powerbi/            Power BI project (TMDL model + PBIR report) and its generator
data/               Pipeline inputs, generated feeds and model artifacts (git-ignored)
.github/workflows/  CI
```

## Quick start (Docker)

Prerequisites: Docker Desktop.

```bash
cp .env.example .env
# Edit .env: set POSTGRES_PASSWORD, JWT_SECRET (32+ chars), ANALYTICS_SERVICE_KEY,
# and BOOTSTRAP_*_PASSWORD (12+ chars with upper, lower, digit, symbol) to create demo users.
docker compose up -d --build
```

| URL | What |
|---|---|
| http://localhost:4200 | AuditIQ web app |
| http://localhost:4200/swagger-ui.html | API docs |
| http://localhost:8080/actuator/health | Backend health (`BACKEND_PORT`) |
| `localhost:5432` | PostgreSQL for Power BI (`POSTGRES_PORT`) |

Demo users (created on an empty database when their password is set): `admin`, `auditor`, `manager`.

### Load the synthetic dataset

Sign in as `auditor` (or `admin`) → **Data Pipeline** → *Synthetic generator* → **Run pipeline**. A 110,000-row run
takes about a minute; the step log and data-quality report update live. Then explore **Transactions** and the
**Overview** trend. To ingest a CSV instead, see [data/README.md](data/README.md).

### Run the audit rules and investigate

As `auditor` → **Audit Exceptions** → **Run audit rules**. This takes about 5 seconds for 110k transactions and
records about 2,100 potential exceptions. Open an exception to see why it was flagged, the thresholds applied and the
related transactions. **Open investigation** starts a case; on **Investigations** you can assign it, add notes, move it
through review and record the human-reviewed outcome. Administrators tune rule thresholds under
**Administration → Audit rules**.

### Train the anomaly model

As `auditor` → **Reports** → **Train new model** (about 10 s), then **Run anomaly detection** (here or on **Audit
Exceptions**). The report shows precision, recall, F1, the confusion matrix, score distributions, a false-positive
breakdown and a comparison with the audit rules. Scores measure how unusual a transaction is; they are not fraud
probabilities. Administrators tune the thresholds under **Administration → Anomaly model**.

### Risk analytics and Power BI

**Risk Analytics** shows the executive, transaction, risk and investigation views for any date range, computed from
the `analytics` views. Auditors and managers can also download those views as CSV. For Power BI, set
`BI_READER_PASSWORD` in `.env` (this creates a read-only login limited to the `analytics` schema), then open
`powerbi/AuditIQ.pbip` in Power BI Desktop and refresh. See [powerbi/README.md](powerbi/README.md).

If a port is already in use on your machine (e.g. an Oracle listener on 8080 or a local PostgreSQL on 5432),
change `BACKEND_PORT` / `POSTGRES_PORT` in `.env`.

## Running tests

| Component | Command |
|---|---|
| Backend (needs Docker for Testcontainers) | `cd backend && mvn verify` |
| Backend without local JDK 21 | `docker run --rm -v "$PWD/backend":/app -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /app maven:3.9-eclipse-temurin-21 mvn -B verify` |
| Analytics service | `cd analytics-service && pip install -r requirements-dev.txt && ruff check . && pytest` |
| Frontend | `cd frontend && npm ci && npm run test:ci` |

Set `TEST_DATABASE_ADMIN_URL=postgresql://user:pw@localhost:5432/postgres` to include the Python pipeline and
audit-rule integration tests (each test module creates and drops a throw-away database). CI does this automatically.

Latest results (Phase 5): backend **104/104**, frontend **82/82**, analytics service **106/106** (incl. 40 database
integration tests), ruff clean, production build clean, Power BI project schema-valid and in sync with its generator.

## Security & governance

* JWT access tokens (15 min) + rotating, hashed refresh tokens with reuse detection
* BCrypt(12) passwords, strong password policy, lockout after 5 failed attempts
* Role-based access (`ADMIN`, `AUDITOR`, `AUDIT_MANAGER`) on API and routes
* Every login, logout, refresh and user change written to `audit_logs` (including failures)
* Self-registration disabled by default; no secrets in source (`.env` is git-ignored)
* Python service is internal-only and requires a shared service key
* Pipeline triggers (including refused ones) and transaction-detail views are audited
* Transaction-level data is limited to Auditors and Audit Managers; Administrators manage the platform, not the data
* Ground-truth anomaly labels are isolated in the `raw` schema and never shown in the application
* Audit findings are labelled as *potential* exceptions; only a person can record an investigation outcome, and every
  rule run, threshold change, exception view and investigation action is audited
* Rule thresholds are validated against a stored schema and bound as SQL parameters; sort keys are whitelisted
* Power BI uses a dedicated read-only login that can see only the `analytics` views; CSV exports are whitelisted,
  formula-safe and audited
* The anomaly model is unsupervised and never reads ground-truth labels; model versions, hyperparameters,
  thresholds and metrics are recorded, and scores are never presented as fraud probabilities

## Branching

* `develop` – integration branch for ongoing work
* `main` – tested, working functionality only (merged from `develop` at deployment)
