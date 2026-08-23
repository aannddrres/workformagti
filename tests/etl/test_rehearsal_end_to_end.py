"""
End-to-end rehearsal: a real load and a real reconciliation, on SQLite.

What this proves: the plan's ordering, the self-FK patch, every transform,
and -- most of all -- that reconciliation actually catches damage. The last
point is why the suite deliberately corrupts a loaded row and requires the
comparison to notice; a reconciliation that only ever reports success is
indistinguishable from one that does not run.

What it does not prove, and does not pretend to: Oracle behaviour. No
IDENTITY columns, no V28 hash-chain trigger, no VARCHAR2 width enforcement.
The run report says so on every rehearsal, and so does this docstring.
"""
from __future__ import annotations

import os
import sqlite3

import pytest

from scripts.etl import load as load_mod
from scripts.etl import preflight, reconcile, spec
from scripts.etl.db import SourceDb, SqliteTarget

TABLES = ("teams", "categories", "users", "articles", "article_read_receipts", "audit_logs")

SOURCE_DDL = """
CREATE TABLE teams (id INTEGER PRIMARY KEY, name TEXT, created_at TEXT);
CREATE TABLE categories (
    id INTEGER PRIMARY KEY, name TEXT, parent_id INTEGER, slug TEXT, icon TEXT,
    pastel_color_class TEXT, is_active INTEGER
);
CREATE TABLE users (
    id INTEGER PRIMARY KEY, email TEXT, name TEXT, department TEXT, position TEXT,
    phone TEXT, role TEXT, is_active INTEGER, last_active TEXT, hashed_password TEXT,
    permissions TEXT, team_id INTEGER, manager_id INTEGER, last_news_viewed_at TEXT,
    card_style TEXT, last_categories_viewed_at TEXT
);
CREATE TABLE articles (
    id INTEGER PRIMARY KEY, title TEXT, content TEXT, category_id INTEGER, tags TEXT,
    target_department TEXT, audience_profile TEXT, created_at TEXT, updated_at TEXT,
    version INTEGER, author_id INTEGER, status TEXT, youtube_id TEXT, published_at TEXT,
    attachment_url TEXT, last_verified_at TEXT, visible_to_tech_info INTEGER,
    visible_to_service_center INTEGER, is_draft INTEGER, quiz_enabled INTEGER
);
CREATE TABLE article_read_receipts (
    id INTEGER PRIMARY KEY, article_id INTEGER, article_title_snapshot TEXT,
    article_version INTEGER, operator_id INTEGER, operator_name_snapshot TEXT,
    operator_email_snapshot TEXT, operator_department_snapshot TEXT, read_at TEXT
);
CREATE TABLE audit_logs (
    id INTEGER PRIMARY KEY, admin_id INTEGER, action TEXT, item_type TEXT, item_id INTEGER,
    timestamp TEXT, category TEXT, details TEXT, admin_name_snapshot TEXT,
    admin_email_snapshot TEXT, item_name_snapshot TEXT, prev_hash TEXT, row_hash TEXT,
    ip_address TEXT, user_agent TEXT
);
"""

NOW = "2026-08-23 09:15:00.000000"


def _specs():
    return [s for s in spec.load_order() if s.target in TABLES]


def _seed(path: str) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(SOURCE_DDL)
    conn.execute("INSERT INTO teams VALUES (1, 'ტექნიკური — ჯგუფი 03', ?)", (NOW,))
    # A child category whose parent has a *higher* id: the case that fails if
    # the loader inserts parent_id before every row exists.
    conn.execute(
        "INSERT INTO categories VALUES (1, 'ტარიფები', 2, 'tariffs', NULL, NULL, 1)"
    )
    conn.execute("INSERT INTO categories VALUES (2, 'ზოგადი', NULL, 'general', NULL, NULL, 1)")
    conn.executemany(
        "INSERT INTO users VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        [
            (
                1, "manager@magti.ge", "ნინო ბერიძე", "ტექნიკური", "უფროსი", "",
                "manager", 1, NOW, "$2b$12$hash", '["reports.export"]', 1, 2, None,
                "corporate", None,
            ),
            (
                2, "admin@magti.ge", "გიორგი კაპანაძე", "ოფისი", None, None,
                "admin", 1, None, "$2b$12$hash2", None, None, None, None, None, None,
            ),
        ],
    )
    conn.execute(
        "INSERT INTO articles VALUES (1, 'ტარიფის ცვლილება', '<p>ტექსტი</p>', 1, 'tag',"
        " 'All', 'all', ?, ?, 3, 2, 'published', NULL, ?, NULL, ?, 1, 0, 0, 1)",
        (NOW, NOW, NOW, NOW),
    )
    conn.execute(
        "INSERT INTO article_read_receipts VALUES (1, 1, 'ტარიფის ცვლილება', 3, 1,"
        " 'ნინო ბერიძე', 'manager@magti.ge', 'ტექნიკური', ?)",
        (NOW,),
    )
    conn.executemany(
        "INSERT INTO audit_logs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        [
            (1, 2, "CREATE", "article", 1, NOW, "content", '{"title": "ტარიფის ცვლილება"}',
             "გიორგი კაპანაძე", "admin@magti.ge", "ტარიფის ცვლილება", None, "a" * 64,
             "10.0.0.5", "Mozilla/5.0"),
            (2, 2, "UPDATE", "article", 1, NOW, "content", None,
             "გიორგი კაპანაძე", "admin@magti.ge", "ტარიფის ცვლილება", "a" * 64, "b" * 64,
             None, None),
        ],
    )
    conn.commit()
    conn.close()


def _create_target(path: str, specs) -> None:
    conn = sqlite3.connect(path)
    for table in specs:
        columns = [c.target for c in table.columns]
        if table.target == "audit_logs":
            columns += ["prev_hash", "row_hash"]  # written by V28's trigger on Oracle
        conn.execute(f"CREATE TABLE {table.target} ({', '.join(columns)})")
    conn.commit()
    conn.close()


@pytest.fixture()
def rehearsal(tmp_path):
    source_path = str(tmp_path / "legacy.db")
    target_path = str(tmp_path / "oracle-rehearsal.db")
    _seed(source_path)
    specs = _specs()
    _create_target(target_path, specs)
    source = SourceDb(f"sqlite:///{source_path}").connect()
    target = SqliteTarget(target_path).connect()
    yield source, target, specs
    source.close()
    target.close()


def test_preflight_passes_on_clean_data(rehearsal):
    source, target, specs = rehearsal
    checks = preflight.run(source, target, specs)
    assert not preflight.blocking(checks), [c.detail for c in preflight.blocking(checks)]


def test_preflight_refuses_a_value_too_wide_for_its_oracle_column(rehearsal):
    source, target, specs = rehearsal
    source._conn.execute("UPDATE users SET name = ? WHERE id = 1", ("ა" * 201,))
    source._conn.commit()
    failures = [c for c in preflight.blocking(preflight.run(source, target, specs))]
    assert any(c.name == "width:users.name" for c in failures)
    # Character semantics, not bytes: 201 Georgian characters is 603 UTF-8
    # bytes, and a byte-counting check would have flagged this at 67.
    assert any("201" in c.detail for c in failures)


def test_preflight_refuses_an_orphaned_foreign_key(rehearsal):
    source, target, specs = rehearsal
    source._conn.execute("UPDATE articles SET author_id = 999 WHERE id = 1")
    source._conn.commit()
    failures = preflight.blocking(preflight.run(source, target, specs))
    assert any(c.name == "fk:articles.author_id" for c in failures)


def test_preflight_reports_empty_strings_without_blocking(rehearsal):
    source, target, specs = rehearsal
    checks = preflight.run(source, target, specs)
    empty = next(c for c in checks if c.name == "empty-string-to-null")
    assert empty.affected == 1  # users.phone = '' in the fixture
    assert empty.fatal is False


def test_load_then_reconcile_is_clean(rehearsal):
    source, target, specs = rehearsal
    result = load_mod.run(source, target, specs)
    assert result.rows_written == 9  # 1 team + 2 categories + 2 users + 1 article + 1 receipt + 2 audit
    report = reconcile.run(source, target, specs)
    assert report.ok, [
        (t.target, t.source_rows, t.target_rows, t.mismatched) for t in report.tables if not t.ok
    ]


def test_self_referencing_rows_survive_the_load(rehearsal):
    source, target, specs = rehearsal
    load_mod.run(source, target, specs)
    # The child inserted before its parent existed still points at it.
    assert target.scalar("SELECT parent_id FROM categories WHERE id = 1") == 2
    assert target.scalar("SELECT manager_id FROM users WHERE id = 1") == 2


def test_booleans_and_json_arrive_in_oracle_form(rehearsal):
    source, target, specs = rehearsal
    load_mod.run(source, target, specs)
    assert target.scalar("SELECT quiz_enabled FROM articles WHERE id = 1") == 1
    assert target.scalar("SELECT permissions FROM users WHERE id = 1") == '["reports.export"]'
    assert target.scalar("SELECT permissions FROM users WHERE id = 2") is None


def test_reconcile_notices_a_changed_value(rehearsal):
    source, target, specs = rehearsal
    load_mod.run(source, target, specs)
    target.execute("UPDATE articles SET title = 'სხვა სათაური' WHERE id = 1")
    target.commit()
    report = reconcile.run(source, target, specs)
    articles = next(t for t in report.tables if t.target == "articles")
    assert articles.mismatched == 1
    assert articles.samples == [(1,)]
    assert not report.ok


def test_reconcile_notices_a_missing_row(rehearsal):
    source, target, specs = rehearsal
    load_mod.run(source, target, specs)
    target.execute("DELETE FROM article_read_receipts WHERE id = 1")
    target.commit()
    report = reconcile.run(source, target, specs)
    receipts = next(t for t in report.tables if t.target == "article_read_receipts")
    assert receipts.missing_in_target == [(1,)]
    assert not report.ok


def test_reconcile_notices_an_extra_row(rehearsal):
    source, target, specs = rehearsal
    load_mod.run(source, target, specs)
    target.execute(
        "INSERT INTO article_read_receipts (id, article_id, article_title_snapshot,"
        " article_version, operator_id, operator_name_snapshot, operator_email_snapshot,"
        " operator_department_snapshot, read_at) VALUES (99, 1, 'x', 3, 1, 'x', 'x', 'x', ?)",
        (NOW,),
    )
    target.commit()
    report = reconcile.run(source, target, specs)
    receipts = next(t for t in report.tables if t.target == "article_read_receipts")
    assert receipts.extra_in_target == [(99,)]


def test_hash_chain_comparison_is_not_claimed_on_a_rehearsal(rehearsal):
    source, target, specs = rehearsal
    load_mod.run(source, target, specs)
    report = reconcile.run(source, target, specs)
    assert report.audit_hash["skipped"] is True
    assert "only an Oracle target" in report.audit_hash["detail"]


def test_dry_run_writes_nothing(rehearsal):
    source, target, specs = rehearsal
    result = load_mod.run(source, target, specs, dry_run=True)
    assert sum(t.rows_read for t in result.tables) == 9  # every row was read
    assert result.rows_written == 0  # and none was written
    assert target.count("articles") == 0


def test_decision_gated_tables_stay_out_of_a_default_run(rehearsal):
    _, _, specs = rehearsal
    assert "messages" not in {s.target for s in specs}


def test_the_v36_1_backfill_statement_is_read_from_the_migration():
    sql = load_mod.read_v36_1_backfill()
    assert sql.startswith("MERGE INTO user_permission_overrides")
    assert not sql.rstrip().endswith(";")
    assert "JSON_TABLE" in sql  # it still reads users.permissions, which the ETL just filled


def test_uploads_reference_scan_finds_inline_and_attachment_links(tmp_path):
    from scripts.etl import uploads

    source_path = str(tmp_path / "legacy.db")
    _seed(source_path)
    conn = sqlite3.connect(source_path)
    conn.execute(
        "UPDATE articles SET content = ?, attachment_url = ? WHERE id = 1",
        ('<img src="/uploads/inline.png">', "/uploads/doc.pdf"),
    )
    conn.commit()
    conn.close()
    source = SourceDb(f"sqlite:///{source_path}").connect()
    try:
        assert uploads.referenced_filenames(source) == {"inline.png", "doc.pdf"}
    finally:
        source.close()


def test_uploads_load_reports_referenced_but_missing_files(tmp_path):
    from scripts.etl import uploads

    source_path = str(tmp_path / "legacy.db")
    _seed(source_path)
    conn = sqlite3.connect(source_path)
    conn.execute("UPDATE articles SET attachment_url = '/uploads/gone.pdf' WHERE id = 1")
    conn.commit()
    conn.close()

    directory = tmp_path / "uploads"
    directory.mkdir()
    (directory / "kept.png").write_bytes(b"\x89PNG\r\n\x1a\nrest")

    target_path = str(tmp_path / "target.db")
    conn = sqlite3.connect(target_path)
    conn.execute(
        "CREATE TABLE stored_files (filename, content_type, byte_size, uploaded_by,"
        " created_at, content)"
    )
    conn.commit()
    conn.close()

    source = SourceDb(f"sqlite:///{source_path}").connect()
    target = SqliteTarget(target_path).connect()
    try:
        report = uploads.load(source, target, str(directory))
        assert report.missing == ["gone.pdf"]
        assert report.unreferenced == ["kept.png"]
        assert report.rows_written == 1
        assert target.scalar("SELECT content_type FROM stored_files") == "image/png"
    finally:
        source.close()
        target.close()


def test_uploads_are_skipped_when_the_directory_is_absent(tmp_path):
    from scripts.etl import uploads

    assert uploads.scan(str(tmp_path / "nope")) == []


def test_report_redacts_the_source_password():
    from scripts.etl import report as report_mod

    payload = report_mod.build(
        target_kind="oracle",
        source_dsn="postgresql://appuser:s3cret@db:5432/magti_portal",
        dry_run=True,
        gated_included=[],
    )
    assert "s3cret" not in payload["source"]
    assert payload["source"] == "postgresql://appuser:***@db:5432/magti_portal"
    assert "s3cret" not in report_mod.to_markdown(payload)


def test_report_states_that_a_rehearsal_proves_no_oracle_behaviour():
    from scripts.etl import report as report_mod

    payload = report_mod.build(
        target_kind="sqlite-rehearsal", source_dsn="sqlite:///x.db", dry_run=False,
        gated_included=[],
    )
    assert payload["proves_oracle_behaviour"] is False
    assert "რეპეტიციაა" in report_mod.to_markdown(payload)


def test_report_lists_every_skipped_decision(tmp_path):
    from scripts.etl import report as report_mod

    payload = report_mod.build(
        target_kind="oracle", source_dsn="sqlite:///x.db", dry_run=False, gated_included=[],
    )
    markdown = report_mod.to_markdown(payload)
    for table in spec.DECISION_REQUIRED:
        assert f"`{table}`" in markdown
    md_path, json_path = report_mod.write(payload, str(tmp_path / "reports"))
    assert os.path.exists(md_path) and os.path.exists(json_path)


def test_a_skipped_hash_check_never_reads_as_a_passed_one(rehearsal):
    """The verdict line is what people quote in the change record."""
    from scripts.etl import report as report_mod

    source, target, specs = rehearsal
    load_mod.run(source, target, specs)
    payload = report_mod.build(
        target_kind=target.kind,
        source_dsn="sqlite:///x.db",
        dry_run=False,
        gated_included=[],
        reconcile_result=reconcile.run(source, target, specs),
    )
    verdict = report_mod.to_markdown(payload).split("## შედეგი:")[1].splitlines()[0]
    assert "ჰეშ-ჯაჭვი შემოწმებული არ არის" in verdict
