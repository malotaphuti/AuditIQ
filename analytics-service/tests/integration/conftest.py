"""Shared fixtures for tests against a real PostgreSQL + pgvector database.

Set TEST_DATABASE_ADMIN_URL (e.g. postgresql://auditiq:pw@localhost:5432/postgres). Each test module gets a
throw-away database with the backend's Flyway migration scripts applied; it is dropped afterwards.
"""

import os
import secrets
from pathlib import Path

import psycopg
import pytest

ADMIN_URL = os.getenv("TEST_DATABASE_ADMIN_URL")
MIGRATIONS = Path(__file__).resolve().parents[3] / "backend" / "src" / "main" / "resources" / "db" / "migration"


def pytest_collection_modifyitems(items):
    if ADMIN_URL:
        return
    skip = pytest.mark.skip(reason="TEST_DATABASE_ADMIN_URL not set")
    for item in items:
        if "integration" in str(item.fspath):
            item.add_marker(skip)


@pytest.fixture(scope="module")
def database_url():
    name = f"auditiq_it_{secrets.token_hex(4)}"
    with psycopg.connect(ADMIN_URL, autocommit=True) as admin:
        admin.execute(f'CREATE DATABASE "{name}"')
    url = ADMIN_URL.rsplit("/", 1)[0] + f"/{name}"
    try:
        with psycopg.connect(url, autocommit=True) as conn:
            for script in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
                conn.execute(script.read_text(encoding="utf-8"))
        yield url
    finally:
        with psycopg.connect(ADMIN_URL, autocommit=True) as admin:
            admin.execute(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')
