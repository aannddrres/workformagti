"""
Command line entry point for the Postgres -> Oracle migration.

    python -m scripts.etl.cli preflight   # read-only: can this run succeed?
    python -m scripts.etl.cli load        # preflight, then load (--dry-run first)
    python -m scripts.etl.cli reconcile   # compare the two databases
    python -m scripts.etl.cli all         # the cutover sequence, in order

Nothing writes without an explicit `load`/`all`, and both refuse to start
while a blocking preflight check is red. `--force` exists for the one case
the runbook allows -- a rerun into a target that is deliberately not empty --
and says so in the report.
"""
from __future__ import annotations

import argparse
import os
import sys

from . import load as load_mod
from . import preflight, reconcile, report, spec, uploads
from .db import BATCH, OracleTarget, SourceDb, SqliteTarget

DEFAULT_REPORT_DIR = "reports/etl"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="scripts.etl.cli", description=__doc__)
    parser.add_argument("command", choices=["preflight", "load", "reconcile", "all"])
    parser.add_argument(
        "--source",
        default=os.environ.get("ETL_SOURCE_DSN", ""),
        help="legacy DSN: postgresql://user:pw@host/db, or sqlite:///magti_portal.db",
    )
    parser.add_argument("--oracle-dsn", default=os.environ.get("ETL_ORACLE_DSN", ""))
    parser.add_argument("--oracle-user", default=os.environ.get("ETL_ORACLE_USER", ""))
    parser.add_argument("--oracle-password", default=os.environ.get("ETL_ORACLE_PASSWORD", ""))
    parser.add_argument(
        "--rehearsal-target",
        default="",
        help="SQLite file to load instead of Oracle -- wiring rehearsal only, proves no Oracle behaviour",
    )
    parser.add_argument("--uploads-dir", default=os.environ.get("ETL_UPLOADS_DIR", "uploads"))
    parser.add_argument("--skip-uploads", action="store_true")
    parser.add_argument(
        "--include",
        default="",
        help=f"comma-separated decision-gated tables to load: {', '.join(sorted(spec.DECISION_REQUIRED))}",
    )
    parser.add_argument("--dry-run", action="store_true", help="transform and count, write nothing")
    parser.add_argument("--force", action="store_true", help="proceed even if the target is not empty")
    parser.add_argument("--batch", type=int, default=BATCH)
    parser.add_argument("--report-dir", default=os.environ.get("ETL_REPORT_DIR", DEFAULT_REPORT_DIR))
    return parser


def open_target(args):
    if args.rehearsal_target:
        return SqliteTarget(args.rehearsal_target).connect()
    if not args.oracle_dsn:
        raise SystemExit("--oracle-dsn (or ETL_ORACLE_DSN) is required unless --rehearsal-target is used")
    return OracleTarget(args.oracle_dsn, args.oracle_user, args.oracle_password).connect()


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.source:
        raise SystemExit("--source (or ETL_SOURCE_DSN) is required")

    gated = frozenset(name.strip() for name in args.include.split(",") if name.strip())
    unknown = spec.unknown_gated(gated)
    if unknown:
        raise SystemExit(f"--include names no decision-gated table: {', '.join(sorted(unknown))}")

    specs = spec.load_order(gated)
    source = SourceDb(args.source).connect()
    target = open_target(args)

    checks = load_result = uploads_result = uploads_verify = reconcile_result = None
    exit_code = 0
    try:
        if args.command in ("preflight", "load", "all"):
            checks = preflight.run(source, target, specs, check_target=not args.force)
            blocking = preflight.blocking(checks)
            for check in checks:
                if not check.ok:
                    print(f"[{check.status}] {check.name}: {check.detail}", file=sys.stderr)
            if blocking and args.command != "preflight":
                print(
                    f"\n{len(blocking)} blocking check(s) failed -- nothing was written.",
                    file=sys.stderr,
                )
                exit_code = 2
            elif blocking:
                exit_code = 2

        if exit_code == 0 and args.command in ("load", "all"):
            load_result = load_mod.run(source, target, specs, dry_run=args.dry_run, batch=args.batch)
            print(f"loaded {load_result.rows_written} row(s) across {len(load_result.tables)} table(s)")
            if not args.skip_uploads:
                uploads_result = uploads.load(
                    source, target, args.uploads_dir, dry_run=args.dry_run
                )
                if not args.dry_run:
                    uploads_verify = uploads.verify(target, uploads_result)

        if exit_code == 0 and args.command in ("reconcile", "all") and not args.dry_run:
            reconcile_result = reconcile.run(source, target, specs)
            print(f"reconciliation: {'OK' if reconcile_result.ok else 'DIFFERENCES FOUND'}")
            if not reconcile_result.ok:
                exit_code = 3

        payload = report.build(
            target_kind=getattr(target, "kind", "unknown"),
            source_dsn=args.source,
            dry_run=args.dry_run,
            gated_included=sorted(gated),
            checks=checks or [],
            load_result=load_result,
            uploads_result=uploads_result,
            uploads_verify=uploads_verify,
            reconcile_result=reconcile_result,
        )
        md_path, json_path = report.write(payload, args.report_dir)
        print(f"report: {md_path}\n        {json_path}")
    finally:
        source.close()
        target.close()
    return exit_code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
