"""Covers the adoption pass in scripts/import_legacy_content.py.

The rest of that script writes to Oracle and is exercised end to end against
a real database. This one rule is worth pinning here instead, because it is
the rule that decides which existing row a source article is bound to -- and
a wrong binding is silent: the import reports "updated", and an article
nobody meant to touch is overwritten on the next run.
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from import_legacy_content import (  # noqa: E402
    SOURCE_TYPE_ARTICLE,
    SOURCE_TYPE_CATEGORY,
    ImportError_,
    _adopt_existing,
)


NOW = __import__("datetime").datetime(2026, 8, 31, 12, 0, 0)


def _source(articles: list[tuple[int, str, int | None]], categories: list[tuple[int, str]]):
    """A stand-in for magti_portal.db carrying only what adoption reads."""
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    connection.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT)")
    connection.execute(
        "CREATE TABLE articles (id INTEGER PRIMARY KEY, title TEXT, category_id INTEGER)"
    )
    connection.executemany("INSERT INTO categories VALUES (?,?)", categories)
    connection.executemany("INSERT INTO articles VALUES (?,?,?)", articles)
    return connection


class FakeCursor:
    """Answers the three statements adoption issues, and records the writes.

    Deliberately not a mock: the point of the test is the decision adoption
    makes from what the database returns, so the returns are the fixture.
    """

    def __init__(self, titles: dict[str, list[int]], names: dict[str, list[int]], mapped=()):
        self.titles = titles
        self.names = names
        self.mapped = list(mapped)  # (source_type, source_id, target_id)
        self.recorded: list[tuple[str, int, int]] = []
        self._result: list[tuple[int, ...]] = []

    def execute(self, sql: str, **params):
        if "FROM legacy_content_imports" in sql:
            self._result = [
                (source_id, target_id)
                for source_type, source_id, target_id in self.mapped
                if source_type == params["source_type"]
            ]
        elif "FROM articles" in sql:
            self._result = [(row_id,) for row_id in self.titles.get(params["title"], [])]
        elif "FROM categories" in sql:
            self._result = [(row_id,) for row_id in self.names.get(params["name"], [])]
        elif "MERGE INTO legacy_content_imports" in sql:
            self.recorded.append((params["source_type"], params["source_id"], params["target_id"]))
            self._result = []
        else:  # pragma: no cover - a statement this fake was never taught
            raise AssertionError(f"unexpected statement: {sql}")

    def fetchall(self):
        return self._result


def test_an_article_already_in_the_target_is_adopted_not_duplicated():
    source = _source(articles=[(17, "როუმინგული ტარიფები", 3)], categories=[(3, "როუმინგი")])
    cursor = FakeCursor(titles={"როუმინგული ტარიფები": [41]}, names={"როუმინგი": [7]})

    adopted = _adopt_existing(cursor, source, "batch", NOW, apply=True)

    assert adopted[SOURCE_TYPE_ARTICLE] == {17: 41}
    assert adopted[SOURCE_TYPE_CATEGORY] == {3: 7}
    assert (SOURCE_TYPE_ARTICLE, 17, 41) in cursor.recorded


def test_two_rows_sharing_a_title_are_refused_rather_than_guessed():
    """categories.name has no unique constraint and titles are free text."""
    source = _source(articles=[(17, "ერთი სათაური", 3)], categories=[(3, "როუმინგი")])
    cursor = FakeCursor(titles={"ერთი სათაური": [41, 88]}, names={"როუმინგი": [7]})

    with pytest.raises(ImportError_) as refusal:
        _adopt_existing(cursor, source, "batch", NOW, apply=True)

    assert "matches 2 rows" in str(refusal.value)
    # Nothing at all is written, not even the unambiguous category: an
    # import that half-adopted would leave the operator to work out which
    # half on a re-run.
    assert cursor.recorded == []


def test_an_article_with_no_counterpart_is_left_for_the_normal_insert():
    source = _source(articles=[(17, "მხოლოდ წყაროშია", None)], categories=[])
    cursor = FakeCursor(titles={}, names={})

    adopted = _adopt_existing(cursor, source, "batch", NOW, apply=True)

    assert adopted[SOURCE_TYPE_ARTICLE] == {}
    assert cursor.recorded == []


def test_a_source_row_already_mapped_is_left_alone():
    """Re-running --adopt-existing must not re-point an existing mapping."""
    source = _source(articles=[(17, "როუმინგული ტარიფები", None)], categories=[])
    cursor = FakeCursor(
        titles={"როუმინგული ტარიფები": [999]},
        names={},
        mapped=[(SOURCE_TYPE_ARTICLE, 17, 41)],
    )

    adopted = _adopt_existing(cursor, source, "batch", NOW, apply=True)

    assert adopted[SOURCE_TYPE_ARTICLE] == {}
    assert cursor.recorded == []


def test_a_dry_run_reports_the_matches_without_writing_the_map():
    source = _source(articles=[(17, "როუმინგული ტარიფები", None)], categories=[])
    cursor = FakeCursor(titles={"როუმინგული ტარიფები": [41]}, names={})

    adopted = _adopt_existing(cursor, source, "batch", NOW, apply=False)

    assert adopted[SOURCE_TYPE_ARTICLE] == {17: 41}
    assert cursor.recorded == []
