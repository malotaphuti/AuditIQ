"""Validation of configurable rule thresholds against the schema stored with each rule (``threshold_schema``).

The backend validates with the same schema when an administrator edits thresholds; the engine re-validates before
every execution so a bad value can never reach a query.
"""

from __future__ import annotations

from typing import Any


class ThresholdError(ValueError):
    """Raised when a rule's thresholds do not satisfy its schema."""


def validate_thresholds(thresholds: dict[str, Any], schema: dict[str, dict]) -> dict[str, Any]:
    """Returns a clean copy of ``thresholds``; every schema key must be present and valid, and no other key allowed."""
    if not isinstance(thresholds, dict):
        raise ThresholdError("thresholds must be an object")
    unknown = set(thresholds) - set(schema)
    if unknown:
        raise ThresholdError(f"unknown threshold(s): {', '.join(sorted(unknown))}")
    clean: dict[str, Any] = {}
    for key, spec in schema.items():
        if key not in thresholds:
            raise ThresholdError(f"missing threshold '{key}'")
        clean[key] = _check(key, thresholds[key], spec)
    return clean


def _check(key: str, value: Any, spec: dict) -> Any:
    kind = spec.get("type")
    if kind == "boolean":
        if not isinstance(value, bool):
            raise ThresholdError(f"'{key}' must be true or false")
        return value
    if kind in ("number", "integer"):
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise ThresholdError(f"'{key}' must be a number")
        if kind == "integer":
            if float(value) != int(value):
                raise ThresholdError(f"'{key}' must be a whole number")
            value = int(value)
        if "min" in spec and value < spec["min"]:
            raise ThresholdError(f"'{key}' must be at least {spec['min']}")
        if "max" in spec and value > spec["max"]:
            raise ThresholdError(f"'{key}' must be at most {spec['max']}")
        return value
    if kind == "list":
        if not isinstance(value, list) or not value or not all(isinstance(v, str) for v in value):
            raise ThresholdError(f"'{key}' must be a non-empty list of values")
        allowed = spec.get("allowed")
        if allowed is not None and (bad := [v for v in value if v not in allowed]):
            raise ThresholdError(f"'{key}' contains unsupported value(s): {', '.join(bad)}")
        return list(dict.fromkeys(value))
    raise ThresholdError(f"'{key}' has an unsupported schema type '{kind}'")
