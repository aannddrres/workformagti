"""One-off / re-runnable: exports the live OpenAPI contract for every route
main.py registers, as the frozen baseline the Java/Spring Boot port must
match bit-for-bit (see docs/JAVA_ORACLE_ANGULAR_MIGRATION.md, Phase 0.2).

Machine-generated from the actual Pydantic response_models and path
operations -- not hand-transcribed -- so it can't drift from what the app
really does. Runs against a throwaway, isolated SQLite file (same pattern
as tests/conftest.py) so it never touches the real dev database.

Usage: python scripts/export_api_contract.py
Output: docs/api-contract/openapi.json
"""
import json
import os
import sys

_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, _PROJECT_ROOT)

_THROWAWAY_DB_PATH = os.path.join(_PROJECT_ROOT, "_openapi_export_scratch.db")
_THROWAWAY_DB_URL = f"sqlite:///{_THROWAWAY_DB_PATH}"

from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

_engine = create_engine(_THROWAWAY_DB_URL, connect_args={"check_same_thread": False})
_SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=_engine)

import database  # noqa: E402
database.engine = _engine
database.SessionLocal = _SessionLocal


def _get_db():
    db = _SessionLocal()
    try:
        yield db
    finally:
        db.close()


database.get_db = _get_db

os.environ["RUN_INIT"] = "1"  # fresh throwaway DB: create every table before
                               # main.py's SQLite _lightweight_migrations()
                               # runs its guarded ADD COLUMN pass over them
import main  # noqa: E402

schema = main.app.openapi()

out_dir = os.path.join(_PROJECT_ROOT, "docs", "api-contract")
os.makedirs(out_dir, exist_ok=True)
out_path = os.path.join(out_dir, "openapi.json")
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(schema, f, ensure_ascii=False, indent=2, sort_keys=True)
    f.write("\n")

n_paths = len(schema.get("paths", {}))
n_ops = sum(len(v) for v in schema.get("paths", {}).values())
print(f"Wrote {out_path}")
print(f"{n_paths} path templates, {n_ops} operations")

_engine.dispose()
for suffix in ("", "-shm", "-wal"):
    p = _THROWAWAY_DB_PATH + suffix
    if os.path.exists(p):
        os.remove(p)
