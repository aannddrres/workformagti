"""
One-shot data migration: retire the "Billing" and "Sales" departments.

Remaps every row currently tagged with those department labels onto the
surviving departments — Billing -> Support, Sales -> Tech — across the three
tables that carry a department string:

    users.department
    articles.target_department
    required_readings.target_department

Run manually (NOT part of the docker-compose `migrate` service, since this is
a one-time business-decision migration, not a schema bootstrap):

    python migrate_departments.py

Safe to re-run: after a successful run there are no more 'Billing'/'Sales'
rows left to touch, so a second run is a no-op (counts come back 0 immediately).
"""
import logging
import sys

from sqlalchemy import text

from database import engine

logging.basicConfig(level=logging.INFO, format="[migrate_departments] %(message)s")
log = logging.getLogger("migrate_departments")

# (table, column) pairs to remap, and the label mapping applied to all of them.
_TARGETS = (
    ("users", "department"),
    ("articles", "target_department"),
    ("required_readings", "target_department"),
)
_REMAP = {
    "Billing": "Support",
    "Sales": "Tech",
}


def _counts(conn, table, column):
    """Return {label: row_count} for the retired labels on one table."""
    row = conn.execute(
        text(
            f"SELECT {column}, COUNT(*) FROM {table} "
            f"WHERE {column} IN ('Billing', 'Sales') GROUP BY {column}"
        )
    ).fetchall()
    return {label: count for label, count in row}


def main() -> int:
    log.info("Pre-migration counts:")
    with engine.connect() as conn:
        for table, column in _TARGETS:
            counts = _counts(conn, table, column)
            log.info("  %s.%s -> %s", table, column, counts or "none")

    # engine.begin() commits on clean exit and rolls back the whole
    # transaction automatically if any statement raises.
    with engine.begin() as conn:
        for table, column in _TARGETS:
            for old, new in _REMAP.items():
                result = conn.execute(
                    text(f"UPDATE {table} SET {column} = :new WHERE {column} = :old"),
                    {"new": new, "old": old},
                )
                log.info("  %s.%s: %s -> %s (%d rows)", table, column, old, new, result.rowcount)

    log.info("Verification — counts must all be 0:")
    all_clear = True
    with engine.connect() as conn:
        for table, column in _TARGETS:
            row = conn.execute(
                text(f"SELECT COUNT(*) FROM {table} WHERE {column} IN ('Billing', 'Sales')")
            ).scalar()
            log.info("  %s.%s remaining Billing/Sales rows: %d", table, column, row)
            if row != 0:
                all_clear = False

    if not all_clear:
        log.error("Verification failed — some rows still reference retired departments.")
        return 1

    log.info("Migration complete. 'Billing' and 'Sales' no longer appear in any of: %s",
              ", ".join(f"{t}.{c}" for t, c in _TARGETS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
