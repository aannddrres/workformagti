"""
Postgres -> Oracle migration for the Magti Portal cutover.

The legacy FastAPI/Postgres portal and the Java/Oracle one are two different
schemas with the same history in them. This package moves that history across
and then proves it arrived: spec.py is the plan, preflight.py refuses to start
a run that cannot finish, load.py preserves ids, and reconcile.py compares the
two databases row by row -- including the audit hash chain, which Oracle
rebuilds independently and must reproduce byte for byte.

Runbook: docs/DATA_MIGRATION_PG_TO_ORACLE_KA.md
"""
