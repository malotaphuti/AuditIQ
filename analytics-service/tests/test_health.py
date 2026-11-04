import pytest
from fastapi.testclient import TestClient

from app import main
from app.config import get_settings


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("ANALYTICS_SERVICE_KEY", "unit-test-key")
    get_settings.cache_clear()
    yield TestClient(main.app)
    get_settings.cache_clear()


def test_health_reports_degraded_when_database_down(client, monkeypatch):
    monkeypatch.setattr(main, "database_is_up", lambda: False)
    body = client.get("/health").json()
    assert body["status"] == "DEGRADED"
    assert body["database"] == "DOWN"


def test_health_reports_up_when_database_up(client, monkeypatch):
    monkeypatch.setattr(main, "database_is_up", lambda: True)
    assert client.get("/health").json()["status"] == "UP"


def test_internal_endpoints_require_service_key(client):
    assert client.get("/internal/ping").status_code == 401
    assert client.get("/internal/ping", headers={"X-Service-Key": "wrong"}).status_code == 401


def test_internal_endpoint_accepts_valid_key(client):
    response = client.get("/internal/ping", headers={"X-Service-Key": "unit-test-key"})
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_internal_endpoints_fail_closed_without_configured_key(monkeypatch):
    monkeypatch.setenv("ANALYTICS_SERVICE_KEY", "")
    get_settings.cache_clear()
    response = TestClient(main.app).get("/internal/ping", headers={"X-Service-Key": ""})
    assert response.status_code == 503
    get_settings.cache_clear()
