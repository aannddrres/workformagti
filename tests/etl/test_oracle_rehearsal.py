"""
The rehearsal that actually proves something: a full ETL run against Oracle.

Skipped unless ETL_ORACLE_DSN points at a database whose schema Flyway has
already migrated (CI does that with `mvn flyway:migrate`; a developer with an
Oracle instance can do the same). Everything the SQLite rehearsal cannot
reach is checked here, because none of it exists anywhere else:

* IDENTITY columns really do accept the migrated ids, and really do resume
  above them afterwards -- asserted by inserting a row and reading the id
  Oracle assigns;
* V28's trigger really does rebuild the audit hash chain to the same values
  the Postgres trigger produced;
* a 500-character Georgian title really does fit VARCHAR2(500 CHAR), and a
  40k-character body really does survive a CLOB batch bind;
* V36_1's permission-override MERGE, re-run after the users exist, really
  does produce the override that would otherwise have been lost;
* the post-load statements from V35/V42 really do fill their snapshots.
"""
from __future__ import annotations

import json
import os

import pytest

from scripts.etl import load as load_mod
from scripts.etl import preflight, reconcile, spec, uploads
from scripts.etl.db import OracleTarget, SourceDb

# pytest puts this file's directory on sys.path (no __init__.py here, so
# rootdir-relative package imports are not available).
import legacy_source

ORACLE_DSN = os.environ.get("ETL_ORACLE_DSN", "")

pytestmark = pytest.mark.skipif(
    not ORACLE_DSN,
    reason="set ETL_ORACLE_DSN (plus ETL_ORACLE_USER/PASSWORD) to run the Oracle rehearsal",
)

PNG = b"\x89PNG\r\n\x1a\n" + b"rehearsal bytes" * 4


def _reset(target: OracleTarget, specs) -> None:
    """Make the run repeatable: empty every table the ETL writes.

    Reverse load order for the foreign keys, the chain tip back to genesis,
    and each identity sequence back to 1 -- the state a freshly migrated
    schema is in.
    """
    for table in reversed(specs):
        target.execute(f"DELETE FROM {table.target}")
    target.execute("DELETE FROM stored_files")
    target.execute("DELETE FROM user_permission_overrides")
    target.execute("UPDATE audit_chain_state SET tip_hash = NULL WHERE id = 1")
    target.commit()
    for table in specs:
        if table.identity:
            target.identity_restore(table.target)


@pytest.fixture(scope="module")
def rehearsal(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("oracle-rehearsal")
    source_path = str(tmp / "legacy.db")
    facts = legacy_source.seed(source_path)

    uploads_dir = tmp / "uploads"
    uploads_dir.mkdir()
    (uploads_dir / "doc.pdf").write_bytes(b"%PDF-1.4 rehearsal")
    (uploads_dir / "inline.png").write_bytes(PNG)

    source = SourceDb(f"sqlite:///{source_path}").connect()
    target = OracleTarget(
        ORACLE_DSN,
        os.environ.get("ETL_ORACLE_USER", ""),
        os.environ.get("ETL_ORACLE_PASSWORD", ""),
    ).connect()
    specs = spec.load_order()
    _reset(target, specs)

    checks = preflight.run(source, target, specs)
    blocking = preflight.blocking(checks)
    assert not blocking, [f"{c.name}: {c.detail}" for c in blocking]

    load_result = load_mod.run(source, target, specs)
    uploads_result = uploads.load(source, target, str(uploads_dir))
    uploads_verify = uploads.verify(target, uploads_result)
    report = reconcile.run(source, target, specs)

    yield {
        "source": source,
        "target": target,
        "specs": specs,
        "facts": facts,
        "checks": checks,
        "load": load_result,
        "uploads": uploads_result,
        "uploads_verify": uploads_verify,
        "report": report,
    }
    source.close()
    target.close()


# -- the crossing itself ----------------------------------------------------


def test_every_planned_row_arrives(rehearsal):
    expected = rehearsal["facts"]["expected_rows"]
    for table, count in expected.items():
        assert rehearsal["target"].count(table) == count, table
    assert rehearsal["load"].rows_written == sum(expected.values())


def test_reconciliation_is_clean(rehearsal):
    report = rehearsal["report"]
    assert report.ok, [
        (t.target, t.source_rows, t.target_rows, t.mismatched, t.missing_in_target)
        for t in report.tables
        if not t.ok
    ]


def test_ids_are_preserved(rehearsal):
    target = rehearsal["target"]
    assert target.scalar("SELECT MAX(id) FROM audit_logs") == 3
    assert target.scalar("SELECT id FROM articles") == 1
    assert target.scalar("SELECT COUNT(*) FROM users WHERE id IN (1, 2, 3)") == 3


def test_identity_resumes_above_the_migrated_ids(rehearsal):
    """The check that a report cannot fake: let Oracle assign the next id."""
    target = rehearsal["target"]
    highest = int(target.scalar("SELECT MAX(id) FROM tags"))
    target.execute("INSERT INTO tags (name, created_at) VALUES ('ახალი', SYSTIMESTAMP)")
    target.commit()
    assigned = int(target.scalar("SELECT MAX(id) FROM tags"))
    assert assigned > highest
    target.execute("DELETE FROM tags WHERE name = 'ახალი'")
    target.commit()


def test_identity_high_water_check_agrees(rehearsal):
    for entry in rehearsal["report"].identity:
        assert entry["ok"], entry


# -- the audit chain --------------------------------------------------------


def test_oracle_rebuilt_the_same_hash_chain_postgres_had(rehearsal):
    """The strongest evidence the migration produces."""
    audit = rehearsal["report"].audit_hash
    assert not audit.get("skipped"), audit["detail"]
    assert audit["ok"], audit
    assert audit["matched"] == len(rehearsal["facts"]["audit"])
    assert audit["mismatched"] == 0


def test_the_migrated_chain_verifies_on_its_own_terms(rehearsal):
    integrity = rehearsal["report"].audit_chain_integrity
    assert integrity["ok"], integrity
    assert integrity["checked"] == len(rehearsal["facts"]["audit"])


def test_the_first_migrated_row_is_the_chain_genesis(rehearsal):
    target = rehearsal["target"]
    assert target.scalar("SELECT prev_hash FROM audit_logs WHERE id = 1") is None
    assert target.scalar("SELECT COUNT(*) FROM audit_logs WHERE row_hash IS NULL") == 0


def test_the_chain_tip_matches_the_last_migrated_row(rehearsal):
    target = rehearsal["target"]
    tip = target.scalar("SELECT tip_hash FROM audit_chain_state WHERE id = 1")
    last = target.scalar("SELECT row_hash FROM audit_logs WHERE id = (SELECT MAX(id) FROM audit_logs)")
    assert tip == last


# -- Oracle types and widths ------------------------------------------------


def test_a_500_character_georgian_title_fits_varchar2_500_char(rehearsal):
    """1500 bytes in AL32UTF8 -- it only fits because the DDL says CHAR."""
    stored = rehearsal["target"].scalar("SELECT title FROM articles WHERE id = 1")
    assert stored == rehearsal["facts"]["title_500"]
    assert len(stored) == 500


def test_a_long_clob_survives_the_batch_bind(rehearsal):
    body = rehearsal["target"].scalar("SELECT content FROM articles WHERE id = 1")
    body = body.read() if hasattr(body, "read") else body
    assert body == rehearsal["facts"]["long_body"]
    assert len(body) > 32000


def test_permissions_json_passes_the_is_json_check(rehearsal):
    """CHECK (permissions IS JSON) accepted the migrated value.

    What comes back depends on the server: 21c reports the IS JSON
    constraint and python-oracledb returns a decoded list, while 19c --
    production -- returns text. The assertion is on the content, which is
    the same on both.
    """
    stored = rehearsal["target"].scalar("SELECT permissions FROM users WHERE id = 3")
    assert reconcile.canonical_json(stored) == reconcile.canonical_json('["reports.export"]')
    assert json.loads(reconcile.canonical_json(stored)) == ["reports.export"]


def test_an_empty_string_arrives_as_null(rehearsal):
    """Oracle's own semantics, stated by preflight rather than discovered later."""
    assert rehearsal["target"].scalar("SELECT phone FROM users WHERE id = 1") is None
    empty = next(c for c in rehearsal["checks"] if c.name == "empty-string-to-null")
    assert empty.affected == 1 and not empty.fatal


def test_booleans_arrive_as_number_flags(rehearsal):
    target = rehearsal["target"]
    assert int(target.scalar("SELECT quiz_enabled FROM articles WHERE id = 1")) == 1
    assert int(target.scalar("SELECT is_draft FROM articles WHERE id = 1")) == 0


def test_timestamps_are_not_shifted(rehearsal):
    stamp = rehearsal["target"].scalar("SELECT created_at FROM articles WHERE id = 1")
    assert stamp.strftime("%Y-%m-%d %H:%M:%S.%f") == legacy_source.NOW


def test_self_referencing_rows_point_where_they_did(rehearsal):
    target = rehearsal["target"]
    assert int(target.scalar("SELECT parent_id FROM categories WHERE id = 1")) == 2
    assert int(target.scalar("SELECT manager_id FROM users WHERE id = 1")) == 2


# -- the post-load statements ----------------------------------------------


def test_v36_1_recreates_the_override_that_would_have_been_lost(rehearsal):
    """The trap: V36_1 ran at Flyway time, against an empty users table."""
    rows = rehearsal["target"].query(
        "SELECT state FROM user_permission_overrides WHERE user_id = 3 AND permission = :1",
        ("reports.export",),
    )
    assert rows and rows[0][0] == "ALLOW"


def test_evidence_snapshots_are_filled_after_the_load(rehearsal):
    target = rehearsal["target"]
    assert target.scalar("SELECT item_title_snapshot FROM required_readings WHERE id = 1") == (
        rehearsal["facts"]["title_500"]
    )
    assert int(target.scalar("SELECT article_id_snapshot FROM article_read_receipts WHERE id = 1")) == 1
    assert int(target.scalar("SELECT article_id_snapshot FROM article_view_logs WHERE id = 1")) == 1
    assert int(target.scalar("SELECT article_id_snapshot FROM quiz_attempts WHERE id = 1")) == 1
    assert target.scalar("SELECT article_title_snapshot FROM quiz_attempts WHERE id = 1") == (
        rehearsal["facts"]["title_500"]
    )


def test_migrated_content_arrives_live_and_not_on_hold(rehearsal):
    target = rehearsal["target"]
    assert target.scalar("SELECT COUNT(*) FROM articles WHERE trashed_at IS NOT NULL") == 0
    assert int(target.scalar("SELECT legal_hold FROM articles WHERE id = 1")) == 0
    assert int(target.scalar("SELECT lock_version FROM articles WHERE id = 1")) == 0
    assert int(target.scalar("SELECT token_version FROM users WHERE id = 1")) == 0


# -- attachments ------------------------------------------------------------


def test_attachments_become_blobs(rehearsal):
    target = rehearsal["target"]
    assert rehearsal["uploads_verify"]["ok"], rehearsal["uploads_verify"]
    stored = target.scalar("SELECT content FROM stored_files WHERE filename = 'inline.png'")
    stored = stored.read() if hasattr(stored, "read") else stored
    assert stored == PNG
    assert target.scalar("SELECT content_type FROM stored_files WHERE filename = 'inline.png'") == (
        "image/png"
    )


def test_every_referenced_attachment_is_present(rehearsal):
    assert rehearsal["uploads"].missing == []
    assert rehearsal["uploads"].referenced == 2


def test_uploads_have_no_invented_uploader(rehearsal):
    assert rehearsal["target"].scalar(
        "SELECT COUNT(*) FROM stored_files WHERE uploaded_by IS NOT NULL"
    ) == 0


# -- what the ETL must not touch -------------------------------------------


def test_tables_outside_the_plan_stay_as_the_migrations_left_them(rehearsal):
    for entry in rehearsal["report"].untouched:
        assert entry["ok"], entry


def test_decision_gated_tables_were_not_loaded(rehearsal):
    target = rehearsal["target"]
    for table in spec.DECISION_REQUIRED:
        assert target.count(table) == 0


def test_the_report_states_that_this_run_proves_oracle_behaviour(rehearsal):
    from scripts.etl import report as report_mod

    payload = report_mod.build(
        target_kind=rehearsal["target"].kind,
        source_dsn="sqlite:///legacy.db",
        dry_run=False,
        gated_included=[],
        checks=rehearsal["checks"],
        load_result=rehearsal["load"],
        uploads_result=rehearsal["uploads"],
        uploads_verify=rehearsal["uploads_verify"],
        reconcile_result=rehearsal["report"],
    )
    assert payload["proves_oracle_behaviour"] is True
    markdown = report_mod.to_markdown(payload)
    assert "რეპეტიციაა" not in markdown  # no SQLite caveat on a real run
    assert "ჰეშ-ჯაჭვი ემთხვევა" in markdown
