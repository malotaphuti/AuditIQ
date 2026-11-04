"""Service-to-service authentication: only the Spring Boot API may call /internal endpoints."""

import hmac

from fastapi import Header, HTTPException, status

from app.config import get_settings


def require_service_key(x_service_key: str | None = Header(default=None)) -> None:
    expected = get_settings().service_key
    if not expected:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Service key is not configured")
    if x_service_key is None or not hmac.compare_digest(x_service_key, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid service key")
