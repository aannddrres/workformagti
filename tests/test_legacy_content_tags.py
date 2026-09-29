"""კ18: the legacy import leaves the old portal's internal tags behind.

An article's tags reach Oracle twice -- as the articles.tags column and as
the search index built from the same text -- so both are checked, on a first
run's INSERT and on a re-run's UPDATE of an article imported before.
"""

from __future__ import annotations

import sqlite3
import sys
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import import_legacy_content  # noqa: E402
from legacy_content import clean_article_tags, searchable_text, trigrams  # noqa: E402


NOW = datetime(2026, 9, 29, 12, 0, 0)


def test_internal_tags_are_dropped_and_the_rest_kept_as_written() -> None:
    assert clean_article_tags("Support,iptv,აქცია,მიგრირებული,სერვისის მართვა") == "iptv,აქცია,სერვისის მართვა"
    # Whole tags only: the department name alone is an ordinary tag.
    assert clean_article_tags("საინფორმაციო (Desk),საინფორმაციო,IPTV,iptv") == "საინფორმაციო,IPTV,iptv"


def test_an_article_left_with_no_tags_gets_null_not_an_empty_string() -> None:
    assert clean_article_tags("მიგრირებული,Support,საინფორმაციო (Desk)") is None
    assert clean_article_tags(None) is None
    assert clean_article_tags("") is None


def _source(rows: list[tuple[int, str, str, str | None]]) -> sqlite3.Connection:
    """A stand-in for magti_portal.db carrying only what the article pass reads."""
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    connection.execute(
        "CREATE TABLE articles (id INTEGER PRIMARY KEY, title TEXT, content TEXT, category_id INTEGER, "
        "tags TEXT, target_department TEXT, audience_profile TEXT, created_at TEXT, updated_at TEXT, "
        "version INTEGER, youtube_id TEXT, published_at TEXT, attachment_url TEXT, last_verified_at TEXT, "
        "visible_to_tech_info INTEGER, visible_to_service_center INTEGER)"
    )
    connection.execute(
        "CREATE TABLE article_history (id INTEGER PRIMARY KEY, article_id INTEGER, version_id INTEGER, "
        "title TEXT, content TEXT, updated_at TEXT, updated_by INTEGER)"
    )
    connection.execute("CREATE TABLE article_target_departments (article_id INTEGER, department TEXT)")
    connection.executemany("INSERT INTO articles (id, title, content, tags) VALUES (?,?,?,?)", rows)
    return connection


class RecordingCursor:
    """Accepts every statement the article pass and the indexer issue; keeps the ones under test."""

    def __init__(self, mapped: dict[int, int]):
        self.mapped = mapped  # source id -> article id from an earlier run
        self.inserted: list[dict] = []
        self.updated: list[dict] = []
        self.indexed: dict[int, set[str]] = {}
        self._result: list[tuple] = []

    def execute(self, sql: str, **params):
        self._result = []
        if "FROM legacy_content_imports" in sql and params["source_type"] == "article":
            self._result = list(self.mapped.items())
        elif sql.startswith("UPDATE articles"):
            self.updated.append(params)

    def executemany(self, sql: str, rows: list[dict]):
        for row in rows:
            self.indexed.setdefault(row["entity_id"], set()).add(row["trigram"])

    def fetchall(self):
        return self._result


def test_both_the_article_row_and_its_search_index_get_the_cleaned_tags(monkeypatch) -> None:
    source = _source([
        (17, "როუმინგი", "<p>ტექსტი</p>", "Support,iptv,მიგრირებული,ბილინგი"),
        (18, "პაუზა", "<p>ტექსტი</p>", "მიგრირებული,საინფორმაციო (Desk)"),
        (19, "შეჩერება", "<p>ტექსტი</p>", "Support,როუტერი"),
    ])
    cursor = RecordingCursor(mapped={18: 500, 19: 501})

    def insert_id(cursor, sql, **params):  # the real one needs oracledb's bind variable
        cursor.inserted.append(params)
        return 600

    monkeypatch.setattr(import_legacy_content, "_insert_id", insert_id)
    result = import_legacy_content._import_articles(
        cursor, source, category_ids={}, valid_assets=set(), author_id=1,
        status="draft", batch="test", now=NOW, apply=True,
    )
    import_legacy_content._index_for_search(cursor, result["mapping"], result["texts"], apply=True)

    assert [row["tags"] for row in cursor.inserted] == ["iptv,ბილინგი"]
    assert {row["article_id"]: row["tags"] for row in cursor.updated} == {500: None, 501: "როუტერი"}

    expected = {600: "iptv,ბილინგი", 500: None, 501: "როუტერი"}
    titles = {600: "როუმინგი", 500: "პაუზა", 501: "შეჩერება"}
    for article_id, tags in expected.items():
        assert cursor.indexed[article_id] == set(trigrams(searchable_text(titles[article_id], "<p>ტექსტი</p>", tags)))
        assert "sup" not in cursor.indexed[article_id]
