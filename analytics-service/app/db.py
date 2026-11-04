"""Database access. The schema is owned by the backend's Flyway migrations; this service never alters it."""

import psycopg

from app.config import get_settings


def conninfo() -> str:
    """libpq connection string derived from the SQLAlchemy-style URL used in configuration."""
    return get_settings().database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def connect() -> psycopg.Connection:
    """Autocommit connection; use ``conn.transaction()`` for atomic units of work."""
    return psycopg.connect(conninfo(), autocommit=True, connect_timeout=5)


def database_is_up() -> bool:
    try:
        with connect() as conn:
            conn.execute("SELECT 1")
        return True
    except Exception:  # noqa: BLE001 - any failure means "down" for health purposes
        return False
