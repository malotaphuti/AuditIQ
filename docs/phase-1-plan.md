# Phase 1 – Foundation: plan and validation

## Scope

| # | Deliverable | Where |
|---|---|---|
| 1 | Repository structure for all services | repo root |
| 2 | Complete PostgreSQL schema (all 18 required tables + raw/staging layers, constraints, indexes) | `backend/src/main/resources/db/migration` |
| 3 | Docker environment: PostgreSQL+pgvector, Spring Boot, FastAPI, Angular/nginx | `docker-compose.yml`, `*/Dockerfile` |
| 4 | Spring Boot API: JWT auth, rotating refresh tokens, RBAC, lockout, audit trail, user admin, overview, system status, OpenAPI | `backend/` |
| 5 | Angular app: login, protected + role-guarded routes, enterprise shell with the 10 required navigation items, live Overview, Administration (users + audit trail) | `frontend/` |
| 6 | Python service skeleton with health check and service-key protected internal API | `analytics-service/` |
| 7 | CI pipeline for all three code bases + Docker build | `.github/workflows/ci.yml` |

Modules delivered in later phases appear in navigation with a "Scheduled for Phase N" state – they contain no
interactive controls and no figures, in line with the "no fake interactions / no mock metrics" standard.

## Key decisions

* **pgvector instead of ChromaDB** – one fewer service; vectors live next to the policy metadata they cite.
* **Flyway owns the schema** – the Python service is a consumer, so there is exactly one migration history.
* **Self-registration off by default** – in a bank, accounts are provisioned by an administrator. `/api/auth/register`
  exists (as specified) but returns 403 unless `AUDITIQ_SELF_REGISTRATION=true`, and can only create Auditors.
* **Demo users from environment variables** – no passwords in source or migrations.
* **Refresh-token rotation with reuse detection** – replaying a used refresh token revokes all of that user's sessions.
* **Audit log in its own transaction** – failed logins are recorded even though the request fails.

## Validation checklist

| Check | How | Result |
|---|---|---|
| Backend unit + integration tests (real PostgreSQL via Testcontainers) | `mvn verify` | see README |
| Python tests + lint | `pytest`, `ruff check` | see README |
| Angular tests + production build | `npm run test:ci`, `ng build` | see README |
| Stack starts from scratch | `docker compose up -d --build` | all services healthy |
| Login as each role, RBAC enforced, audit trail populated | manual smoke test against the running stack | see README |
