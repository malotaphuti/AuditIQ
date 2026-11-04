# AuditIQ – AI-Powered Banking Audit & Risk Intelligence Platform

AuditIQ helps internal audit teams turn transaction data into **evidence-based audit intelligence**: automated data
preparation, SQL audit rules, Isolation Forest anomaly detection, Power BI / Angular dashboards, and a RAG assistant +
controlled investigation agent – with human review of every outcome.

> **Synthetic data only.** Findings are *potential audit exceptions* for human review, never determinations of fraud.

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Foundation: schema, Docker, Spring Boot API, auth/RBAC, audit trail, Angular shell | ✅ Complete |
| 2 | Synthetic data generator & ingestion pipeline | ⏳ Next |
| 3 | SQL audit rules, exceptions, investigation management | — |
| 4 | Isolation Forest model & evaluation | — |
| 5 | Angular analytics + Power BI | — |
| 6 | RAG assistant | — |
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
* API documentation: Swagger UI at `http://localhost:4200/swagger-ui.html` once running

## Repository layout

```
backend/            Spring Boot 3.5 / Java 21 – REST API, security, Flyway migrations
frontend/           Angular 20 + Angular Material – SPA served by nginx
analytics-service/  Python 3.12 FastAPI – pipeline, rules engine, ML, RAG, agent
database/init/      One-time PostgreSQL extension setup
docs/               Architecture, ERD, phase plans
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

If a port is already in use on your machine (e.g. an Oracle listener on 8080 or a local PostgreSQL on 5432),
change `BACKEND_PORT` / `POSTGRES_PORT` in `.env`.

## Running tests

| Component | Command |
|---|---|
| Backend (needs Docker for Testcontainers) | `cd backend && mvn verify` |
| Backend without local JDK 21 | `docker run --rm -v "$PWD/backend":/app -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /app maven:3.9-eclipse-temurin-21 mvn -B verify` |
| Analytics service | `cd analytics-service && pip install -r requirements-dev.txt && ruff check . && pytest` |
| Frontend | `cd frontend && npm ci && npm run test:ci` |

Phase 1 results: backend **39/39**, frontend **29/29**, analytics service **5/5**, ruff clean, production build OK,
full stack healthy under Docker Compose and smoke-tested (login per role, RBAC 401/403, audit trail, overview,
service-to-service status).

## Security & governance (Phase 1)

* JWT access tokens (15 min) + rotating, hashed refresh tokens with reuse detection
* BCrypt(12) passwords, strong password policy, lockout after 5 failed attempts
* Role-based access (`ADMIN`, `AUDITOR`, `AUDIT_MANAGER`) on API and routes
* Every login, logout, refresh and user change written to `audit_logs` (including failures)
* Self-registration disabled by default; no secrets in source (`.env` is git-ignored)
* Python service is internal-only and requires a shared service key

## Branching

* `develop` – integration branch for ongoing work
* `main` – tested, working functionality only (merged from `develop` at the end of each validated phase)
