"""
Value transforms between the Postgres source column and the Oracle target
column.

Every transform is a pure function of a single source value. That is
deliberate: the reconciliation pass (reconcile.py) re-applies the *same*
function to the source row before comparing it with what Oracle actually
stored, so a transform that needed row context would silently make the two
sides incomparable.

None always maps to None. NULL is a fact in both databases and no transform
here invents a value for it -- the DDL default is the only thing allowed to
do that, and only for columns the plan does not list at all.
"""
from __future__ import annotations

import json
from datetime import date, datetime
from typing import Any, Callable


def passthrough(value: Any) -> Any:
    """VARCHAR2/NUMBER/TIMESTAMP columns whose Python value already matches.

    Timestamps are the important case: models.py stores naive local Tbilisi
    datetimes (`get_tbilisi_time`), and Oracle's TIMESTAMP(6) is equally
    zone-less. Converting here -- to UTC, or to a TIMESTAMP WITH TIME ZONE --
    would shift every historical audit row by four hours and break the
    hash-chain comparison in reconcile.py. So: no conversion.
    """
    return value


def bool_to_number(value: Any) -> Any:
    """Postgres BOOLEAN -> Oracle NUMBER(1).

    Oracle 19c has no BOOLEAN type; the whole schema uses NUMBER(1) 0/1
    (see docs/QUESTIONS_FOR_IT.md section 5 for why that is load-bearing).
    SQLite -- the local dev source -- already stores 0/1 integers, which is
    why ints pass through unchanged rather than being rejected.
    """
    if value is None:
        return None
    if isinstance(value, bool):
        return 1 if value else 0
    if isinstance(value, int):
        if value in (0, 1):
            return value
        raise ValueError(f"boolean column holds a non-boolean integer: {value!r}")
    raise TypeError(f"boolean column holds {type(value).__name__}: {value!r}")


def json_to_clob(value: Any) -> Any:
    """users.permissions: Postgres JSON / SQLite TEXT -> Oracle CLOB.

    The target column carries CHECK (permissions IS JSON) (V3), so the text
    written has to parse. A NULL stays NULL -- Oracle evaluates the CHECK to
    UNKNOWN for it, which passes, and that matches the source's own nullable
    column. An empty string would *fail* the check, so it is normalised to
    NULL rather than quietly turned into "[]": an empty string is a legacy
    write bug, not an empty permission list, and inventing "[]" here would
    hide it from the report.
    """
    if value is None:
        return None
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return None
        json.loads(stripped)  # raises on malformed legacy text -- preflight reports it
        return stripped
    raise TypeError(f"JSON column holds {type(value).__name__}: {value!r}")


def to_timestamp(value: Any) -> Any:
    """TIMESTAMP(6) columns: whatever the source hands back -> a datetime.

    Postgres already returns datetime objects, so this is a no-op there. The
    SQLite dev database returns text, and Oracle will not accept a string for
    a TIMESTAMP column unless it happens to match the session's
    NLS_TIMESTAMP_FORMAT -- which is how a rehearsal run from the local dev
    database would fail on binding, long after preflight said everything was
    fine. Converting here makes both sources behave the same.

    No time zone is attached. models.py stores naive Tbilisi local time
    (`get_tbilisi_time`) and Oracle's TIMESTAMP(6) is equally zone-less;
    attaching one would move every historical row.
    """
    if value is None or isinstance(value, datetime):
        return value
    if isinstance(value, date):
        return datetime(value.year, value.month, value.day)
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            return datetime.fromisoformat(text.replace(" ", "T"))
        except ValueError as exc:
            raise ValueError(f"timestamp column holds unparseable text: {value!r}") from exc
    raise TypeError(f"timestamp column holds {type(value).__name__}: {value!r}")


TRANSFORMS: dict[str, Callable[[Any], Any]] = {
    "passthrough": passthrough,
    "bool_to_number": bool_to_number,
    "json_to_clob": json_to_clob,
    "timestamp": to_timestamp,
}


def apply(name: str, value: Any) -> Any:
    try:
        fn = TRANSFORMS[name]
    except KeyError:  # pragma: no cover - guarded by the spec coverage test
        raise KeyError(f"unknown transform {name!r}") from None
    return fn(value)
