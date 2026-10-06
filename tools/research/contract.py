"""Offline M0 fixture checks and small research models, NOT app production code.

No authentication, network, storage or Android implementation is included.
"""
from datetime import datetime
import json
import math
from pathlib import Path
import re

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "docs/research/m0"
EXPIRY = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z", re.ASCII)
FORBIDDEN = {"access_token", "refresh_token", "id_token", "authorization", "authorization_code", "code_verifier", "device_auth_id", "user_code", "cookie", "email", "sub", "account_id", "user_id", "profile_user_id", "profile_image_url"}


def strict_json(text):
    def reject(value):
        raise ValueError("nonfinite JSON")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    result = json.loads(text, parse_constant=reject, object_pairs_hook=unique)
    assert_finite(result)
    return result


def read(path):
    return strict_json(Path(path).read_text(encoding="utf-8"))


def assert_finite(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("nonfinite number")
    if isinstance(value, dict):
        for child in value.values():
            assert_finite(child)
    elif isinstance(value, list):
        for child in value:
            assert_finite(child)


def privacy_guard(value):
    """Bounded fixture key check, not a security/PII scanner certification."""
    if isinstance(value, dict):
        if FORBIDDEN.intersection(key.lower() for key in value):
            raise ValueError("forbidden credential/profile field")
        for child in value.values():
            privacy_guard(child)
    elif isinstance(value, list):
        for child in value:
            privacy_guard(child)


def schema_validate(value, name):
    assert_finite(value)
    schema = read(DATA / "schemas" / name)
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(value)


def validate_snapshot(snapshot):
    schema_validate(snapshot, "snapshot.schema.json")
    now = snapshot["evaluatedAtEpochSeconds"]
    for name, window in snapshot["windows"].items():
        if window["state"] in {"unavailable", "malformed", "unsupported"}:
            if any(window[key] is not None for key in ("usedPercent", "windowSeconds", "resetAtEpochSeconds", "resetAfterSecondsRaw")):
                raise ValueError("unavailable data cannot be zero")
        else:
            duration = 18000 if name == "fiveHour" else 604800
            if window["usedPercent"] is None or window["windowSeconds"] != duration:
                raise ValueError("available window needs percent and correct duration")
        if (window["resetSource"] == "unknown") != (window["resetAtEpochSeconds"] is None):
            raise ValueError("reset provenance mismatch")
        if window["state"] == "reset_due" and not window["resetAtEpochSeconds"] <= now:
            raise ValueError("reset due needs a passed instant")
    bank = snapshot["bankedResets"]
    if bank["state"] in {"unavailable", "unsupported", "malformed"} and bank["reportedAvailableCount"] is not None:
        raise ValueError("unavailable count cannot be zero")
    keys = [item["key"] for item in bank["items"]]
    if len(keys) != len(set(keys)):
        raise ValueError("duplicate reset keys")
    for item in bank["items"]:
        expiry = item["expiresAtEpochSeconds"]
        if item["expiryKnowledge"] == "known":
            if expiry is None or item["locallyExpired"] != (expiry <= now):
                raise ValueError("expiry evaluation mismatch")
        elif expiry is not None or item["locallyExpired"] is not None:
            raise ValueError("unknown expiry is not infinite")
    observed = snapshot["observedAtEpochSeconds"]
    if observed > now or (snapshot["freshness"] == "fresh" and now - observed > 900):
        raise ValueError("freshness/clock mismatch")


def expiry_seconds(value):
    """Calendar-valid known UTC form; unknown is None, never infinite.

    Intentionally only the evidenced Z/0..6-fractional-digit grammar. Other
    ISO-8601 shapes stay unknown until a reviewed contract change. Subseconds
    are truncated to epoch seconds; the safe source string is retained by the
    owner receipt for precision/provenance, not silently reinterpreted.
    """
    if not isinstance(value, str) or not EXPIRY.fullmatch(value):
        return None
    try:
        stamp = int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())
    except (ValueError, OverflowError):
        return None
    return stamp if stamp > 0 else None


def select_windows(raw):
    """Research selection oracle, not a mobile DTO parser."""
    output = {}
    for name, duration in (("fiveHour", 18000), ("weekly", 604800)):
        candidates = [(slot, raw.get(slot)) for slot in ("primary_window", "secondary_window")
                      if isinstance(raw.get(slot), dict)
                      and type(raw[slot].get("limit_window_seconds")) is int
                      and raw[slot]["limit_window_seconds"] == duration]
        if not candidates:
            output[name] = {"state": "unavailable", "usedPercent": None}
        elif len(candidates) > 1:
            output[name] = {"state": "ambiguous", "usedPercent": None}
        else:
            slot, value = candidates[0]
            percent = value.get("used_percent")
            if type(percent) not in (int, float) or not math.isfinite(percent) or not 0 <= percent <= 100:
                output[name] = {"state": "malformed", "usedPercent": None}
            else:
                output[name] = {"state": "available", "usedPercent": percent, "sourceSlot": slot}
    return output


def read_outcome(status, terminal_refresh=False):
    """Read-stage policy model; device poll's 403/404 pending is distinct."""
    if status == 200:
        return "ok"
    if status == 401:
        return "reauthorize" if terminal_refresh else "one_serialized_refresh"
    if status == 403:
        return "forbidden_not_plan_or_zero"
    if status == 429:
        return "retry_after_preserve_credentials"
    return "transient_keep_last_success"


def countdown(reset, now):
    if reset is None:
        return "unknown"
    remaining = reset - now
    if remaining <= 0:
        return "awaiting refresh"
    if remaining < 3600:
        return "<1h"
    days, remainder = divmod(remaining, 86400)
    return f"{days}d {remainder // 3600}h"
