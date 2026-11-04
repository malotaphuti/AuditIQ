-- Runs once when the PostgreSQL volume is first created.
-- Schema objects are managed by Flyway (backend/src/main/resources/db/migration); this only prepares extensions.
CREATE EXTENSION IF NOT EXISTS vector;
