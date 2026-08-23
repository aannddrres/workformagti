"""
Database adapters.

Two source dialects (Postgres for the real cutover, SQLite for the local dev
database and the tests) and two targets: Oracle, and a SQLite "rehearsal"
target that lets the whole pipeline be exercised without an Oracle instance.

The rehearsal target is honest about what it is not: it has no IDENTITY
columns, no V28 hash-chain trigger and no VARCHAR2 width enforcement, so a
green rehearsal proves the plan's wiring, ordering and reconciliation logic
-- not Oracle behaviour. Only a run against a real Oracle instance proves
that, and the run report says which one produced it.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime
from dataclasses import dataclass
from typing import Any, Iterator, Sequence

BATCH = 1000


class DriverMissing(RuntimeError):
    pass


# ---------------------------------------------------------------- source ---


@dataclass
class SourceDb:
    """Read-only access to the legacy portal database."""

    dsn: str
    _conn: Any = None
    dialect: str = "sqlite"

    def connect(self) -> "SourceDb":
        if self.dsn.startswith(("postgresql://", "postgres://", "postgresql+psycopg2://")):
            self.dialect = "postgres"
            try:
                import psycopg2  # noqa: PLC0415
            except ImportError as exc:  # pragma: no cover - driver present in requirements.txt
                raise DriverMissing("psycopg2 is required to read the Postgres source") from exc
            dsn = self.dsn.replace("postgresql+psycopg2://", "postgresql://", 1)
            self._conn = psycopg2.connect(dsn)
            # A single repeatable-read snapshot for the whole run: counts taken
            # in preflight must describe the same database state the load reads,
            # or reconciliation compares two different moments in time.
            self._conn.set_session(isolation_level="REPEATABLE READ", readonly=True)
        else:
            self.dialect = "sqlite"
            path = self.dsn.replace("sqlite:///", "", 1).replace("sqlite://", "", 1)
            self._conn = sqlite3.connect(path)
        return self

    @property
    def placeholder(self) -> str:
        return "%s" if self.dialect == "postgres" else "?"

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def query(self, sql: str, params: Sequence[Any] = ()) -> list[tuple]:
        cur = self._conn.cursor()
        try:
            cur.execute(sql, tuple(params))
            return list(cur.fetchall())
        finally:
            cur.close()

    def scalar(self, sql: str, params: Sequence[Any] = ()) -> Any:
        rows = self.query(sql, params)
        return rows[0][0] if rows else None

    def count(self, table: str) -> int:
        return int(self.scalar(f"SELECT COUNT(*) FROM {table}") or 0)

    def table_exists(self, table: str) -> bool:
        if self.dialect == "postgres":
            return bool(
                self.scalar(
                    "SELECT COUNT(*) FROM information_schema.tables "
                    "WHERE table_schema = current_schema() AND table_name = %s",
                    (table,),
                )
            )
        return bool(
            self.scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?", (table,))
        )

    def stream(
        self, table: str, columns: Sequence[str], order_by: str, batch: int = BATCH
    ) -> Iterator[tuple]:
        """Yield source rows in a deterministic order, batch by batch.

        Deterministic ordering is not a nicety: audit_logs must be inserted in
        ascending id order for its hash chain to reproduce, and reconcile.py
        compares the two sides row by row without buffering either.
        """
        cols = ", ".join(columns)
        cur = self._conn.cursor()
        try:
            cur.execute(f"SELECT {cols} FROM {table} ORDER BY {order_by}")
            while True:
                rows = cur.fetchmany(batch)
                if not rows:
                    return
                yield from rows
        finally:
            cur.close()


# ---------------------------------------------------------------- target ---


class OracleTarget:
    """The real target: Oracle 19c, schema at Flyway V42."""

    kind = "oracle"

    def __init__(self, dsn: str, user: str, password: str) -> None:
        self.dsn, self.user, self.password = dsn, user, password
        self._conn = None
        self._oracledb = None

    def connect(self) -> "OracleTarget":
        try:
            import oracledb  # noqa: PLC0415
        except ImportError as exc:
            raise DriverMissing(
                "oracledb is required for the Oracle target (pip install -r requirements-dev.txt)"
            ) from exc
        self._oracledb = oracledb
        self._conn = oracledb.connect(user=self.user, password=self.password, dsn=self.dsn)
        return self

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def execute(self, sql: str, params: Sequence[Any] = ()) -> None:
        cur = self._conn.cursor()
        try:
            cur.execute(sql, tuple(params))
        finally:
            cur.close()

    def query(self, sql: str, params: Sequence[Any] = ()) -> list[tuple]:
        cur = self._conn.cursor()
        try:
            cur.execute(sql, tuple(params))
            return list(cur.fetchall())
        finally:
            cur.close()

    def scalar(self, sql: str, params: Sequence[Any] = ()) -> Any:
        rows = self.query(sql, params)
        return rows[0][0] if rows else None

    def count(self, table: str) -> int:
        return int(self.scalar(f"SELECT COUNT(*) FROM {table}") or 0)

    def insert_many(
        self, table: str, columns: Sequence[str], bind_types: dict[str, str], rows: list[tuple]
    ) -> None:
        """Batch insert, with the bind type declared wherever the default is wrong.

        Two columns' worth of reasons, both of which fail quietly or late:

        * a CLOB past 32k cannot go through the default string bind at all
          (DPY-4007), and article bodies routinely are;
        * a Python datetime must be bound as TIMESTAMP, not DATE. Oracle's
          DATE type has no fractional seconds, so the wrong bind does not
          raise -- it silently drops microseconds, which then shows up as
          every audit hash disagreeing with the source and no obvious reason
          why.
        """
        if not rows:
            return
        binds = ", ".join(f":{i + 1}" for i in range(len(columns)))
        sql = f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({binds})"
        cur = self._conn.cursor()
        try:
            if bind_types:
                declared = {
                    "clob": self._oracledb.DB_TYPE_CLOB,
                    "blob": self._oracledb.DB_TYPE_BLOB,
                    "timestamp": self._oracledb.DB_TYPE_TIMESTAMP,
                }
                cur.setinputsizes(*(declared.get(bind_types.get(name)) for name in columns))
            cur.executemany(sql, rows)
        finally:
            cur.close()

    def update_many(self, table: str, set_columns: Sequence[str], pk: Sequence[str], rows: list[tuple]) -> None:
        if not rows:
            return
        sets = ", ".join(f"{c} = :{i + 1}" for i, c in enumerate(set_columns))
        where = " AND ".join(f"{c} = :{len(set_columns) + i + 1}" for i, c in enumerate(pk))
        cur = self._conn.cursor()
        try:
            cur.executemany(f"UPDATE {table} SET {sets} WHERE {where}", rows)
        finally:
            cur.close()

    def stream(self, table: str, columns: Sequence[str], order_by: str, batch: int = BATCH) -> Iterator[tuple]:
        cur = self._conn.cursor()
        try:
            cur.execute(f"SELECT {', '.join(columns)} FROM {table} ORDER BY {order_by}")
            while True:
                rows = cur.fetchmany(batch)
                if not rows:
                    return
                for row in rows:
                    yield tuple(self._read_lob(v) for v in row)
        finally:
            cur.close()

    @staticmethod
    def _read_lob(value: Any) -> Any:
        return value.read() if hasattr(value, "read") else value

    # -- identity handling --------------------------------------------------
    #
    # Every table in the schema uses GENERATED ALWAYS AS IDENTITY, which
    # rejects an explicit id (ORA-32795). Preserving ids is non-negotiable:
    # article_view_logs, read receipts, required_readings and every audit row
    # reference content by id, and the audit hash chain hashes the id itself.
    # So the column is switched to BY DEFAULT for the load and switched back
    # afterwards -- with START WITH LIMIT VALUE, which sets the identity
    # sequence to max(id)+1 so the first row the application writes after
    # cutover cannot collide with a migrated one.

    def identity_allow_explicit(self, table: str, column: str = "id") -> None:
        self.execute(f"ALTER TABLE {table} MODIFY ({column} GENERATED BY DEFAULT AS IDENTITY)")

    def identity_restore(self, table: str, column: str = "id") -> None:
        self.execute(
            f"ALTER TABLE {table} MODIFY "
            f"({column} GENERATED ALWAYS AS IDENTITY (START WITH LIMIT VALUE))"
        )

    def commit(self) -> None:
        self._conn.commit()

    def rollback(self) -> None:
        self._conn.rollback()


# The rehearsal target stores timestamps as ISO text. Python 3.12 deprecated
# the implicit datetime adapter, so it is declared here rather than inherited
# from a version that still happens to provide one.
sqlite3.register_adapter(datetime, lambda value: value.isoformat(sep=" "))


class SqliteTarget:
    """Rehearsal target -- wiring and ordering only, not Oracle semantics."""

    kind = "sqlite-rehearsal"

    def __init__(self, path: str) -> None:
        self.path = path
        self._conn: sqlite3.Connection | None = None

    def connect(self) -> "SqliteTarget":
        self._conn = sqlite3.connect(self.path)
        self._conn.execute("PRAGMA foreign_keys = ON")
        return self

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def execute(self, sql: str, params: Sequence[Any] = ()) -> None:
        self._conn.execute(sql, tuple(params))

    def query(self, sql: str, params: Sequence[Any] = ()) -> list[tuple]:
        return list(self._conn.execute(sql, tuple(params)).fetchall())

    def scalar(self, sql: str, params: Sequence[Any] = ()) -> Any:
        rows = self.query(sql, params)
        return rows[0][0] if rows else None

    def count(self, table: str) -> int:
        return int(self.scalar(f"SELECT COUNT(*) FROM {table}") or 0)

    def insert_many(
        self, table: str, columns: Sequence[str], bind_types: dict[str, str], rows: list[tuple]
    ) -> None:
        if not rows:
            return
        binds = ", ".join("?" for _ in columns)
        self._conn.executemany(
            f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({binds})", rows
        )

    def update_many(self, table: str, set_columns: Sequence[str], pk: Sequence[str], rows: list[tuple]) -> None:
        if not rows:
            return
        sets = ", ".join(f"{c} = ?" for c in set_columns)
        where = " AND ".join(f"{c} = ?" for c in pk)
        self._conn.executemany(f"UPDATE {table} SET {sets} WHERE {where}", rows)

    def stream(self, table: str, columns: Sequence[str], order_by: str, batch: int = BATCH) -> Iterator[tuple]:
        cur = self._conn.execute(f"SELECT {', '.join(columns)} FROM {table} ORDER BY {order_by}")
        while True:
            rows = cur.fetchmany(batch)
            if not rows:
                return
            yield from rows

    def identity_allow_explicit(self, table: str, column: str = "id") -> None:
        return  # SQLite always allows an explicit rowid

    def identity_restore(self, table: str, column: str = "id") -> None:
        return

    def commit(self) -> None:
        self._conn.commit()

    def rollback(self) -> None:
        self._conn.rollback()
