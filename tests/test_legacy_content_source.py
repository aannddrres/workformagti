"""The source side of the legacy content import: department mapping, the
HTML sanitiser and the approved inventory of magti_portal.db. Exercises
scripts/legacy_content.py without a database; the Oracle writer in
scripts/import_legacy_content.py only ever sees what passed these checks.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from legacy_content import (  # noqa: E402
    EXPECTED_ARTICLES,
    EXPECTED_ARTICLE_HISTORY,
    EXPECTED_ASSETS,
    SanitizationLossError,
    SanitizationStats,
    SourceSafetyError,
    assert_expected_inventory,
    build_source_inventory,
    local_upload_filename,
    normalize_category_icon,
    normalize_department,
    normalize_search_entity_type,
    open_source_database,
    referenced_asset_names,
    sanitize_html,
    source_articles,
    source_histories,
    trigrams,
)


SOURCE_DB = ROOT / "magti_portal.db"
SOURCE_UPLOADS = ROOT / "uploads"


def test_department_mapping_preserves_approved_labels() -> None:
    assert normalize_department("საინფო") == "საინფორმაციო"
    assert normalize_department("Informational") == "საინფორმაციო"
    assert normalize_department("ტექნიკური") == "ტექნიკური"
    assert normalize_department("ოფისი") == "ოფისი"
    assert normalize_department("All") == "All"

    with pytest.raises(SourceSafetyError, match="Unknown department"):
        normalize_department("Support")


def test_category_icon_is_repaired_or_left_to_the_portal_fallback() -> None:
    # The source's roaming row stores `fa_wifi`; verbatim it drew an empty tile.
    assert normalize_category_icon("fa_wifi") == "fa-wifi"
    assert normalize_category_icon(" fa-gift ") == "fa-gift"
    assert normalize_category_icon(None) is None
    assert normalize_category_icon("") is None
    assert normalize_category_icon("wifi") is None
    assert normalize_category_icon("fa-solid fa-gift") is None


def test_local_upload_filename_rejects_external_data_and_traversal() -> None:
    assert local_upload_filename("/uploads/abc.png") == "abc.png"
    assert local_upload_filename("uploads/photo.jpg?cache=1") == "photo.jpg"
    assert local_upload_filename("https://example.com/uploads/abc.png") is None
    assert local_upload_filename("data:image/png;base64,AAAA") is None
    assert local_upload_filename("/uploads/nested/abc.png") is None
    assert local_upload_filename("/uploads/not-an-image.pdf") is None


def test_sanitizer_keeps_structure_and_verified_local_images() -> None:
    source = (
        '<div onclick="steal()"><script>alert(1)</script><h2>სათაური</h2>'
        '<img src="/uploads/good.png" onerror="steal()">'
        '<img src="https://example.com/external.png">'
        '<a href="javascript:alert(1)">ბმული</a><custom>ტექსტი</custom></div>'
    )
    sanitized, report = sanitize_html(
        source,
        source_article_id=17,
        valid_assets={"good.png"},
    )

    assert "script" not in sanitized
    assert "onclick" not in sanitized
    assert "onerror" not in sanitized
    assert "javascript:" not in sanitized
    assert "https://example.com/external.png" not in sanitized
    assert '<img src="/uploads/good.png"' in sanitized
    assert "<custom>" not in sanitized
    assert "ტექსტი" in sanitized
    assert report.retained_images == 1
    assert report.removed_images == 1


def test_sanitizer_fails_on_material_text_loss() -> None:
    safe_text = "ა" * 150
    dangerous_text = "ბ" * 120
    with pytest.raises(SanitizationLossError):
        sanitize_html(
            f"<p>{safe_text}</p><form><p>{dangerous_text}</p></form>",
            source_article_id=99,
            valid_assets=set(),
        )


def test_search_entity_types_match_the_spring_contract() -> None:
    assert normalize_search_entity_type("article") == "ARTICLE"
    assert normalize_search_entity_type("NEWS") == "NEWS"
    assert normalize_search_entity_type("video") == "VIDEO"
    with pytest.raises(SourceSafetyError, match="Unknown search entity type"):
        normalize_search_entity_type("document")


def test_trigrams_are_distinct_and_unicode_safe() -> None:
    assert trigrams("აბგაბგ") == ["აბგ", "ბგა", "გაბ"]
    assert trigrams("ab") == []


@pytest.mark.skipif(not SOURCE_DB.is_file() or not SOURCE_UPLOADS.is_dir(), reason="magti_portal.db and uploads/ are not on this machine")
def test_approved_source_inventory_matches_manifest() -> None:
    inventory = build_source_inventory(SOURCE_DB, SOURCE_UPLOADS)
    assert_expected_inventory(inventory)
    assert inventory.articles == EXPECTED_ARTICLES
    assert inventory.article_history == EXPECTED_ARTICLE_HISTORY
    assert inventory.assets == EXPECTED_ASSETS


@pytest.mark.skipif(not SOURCE_DB.is_file() or not SOURCE_UPLOADS.is_dir(), reason="magti_portal.db and uploads/ are not on this machine")
def test_every_approved_source_document_sanitizes_without_material_loss() -> None:
    source = open_source_database(SOURCE_DB)
    try:
        articles = source_articles(source)
        histories = source_histories(source)
        assets = set(referenced_asset_names(
            [str(row["content"] or "") for row in articles]
            + [str(row["content"] or "") for row in histories]
        ))
        reports = {int(row["id"]): SanitizationStats(int(row["id"])) for row in articles}
        for row in articles:
            sanitize_html(
                str(row["content"] or ""),
                source_article_id=int(row["id"]),
                valid_assets=assets,
                stats=reports[int(row["id"])],
            )
        for row in histories:
            sanitize_html(
                str(row["content"] or ""),
                source_article_id=int(row["article_id"]),
                valid_assets=assets,
                stats=reports[int(row["article_id"])],
            )
    finally:
        source.close()

    assert len(articles) == 122
    assert len(histories) == 132
    assert len(assets) == 429
    assert sum(report.documents for report in reports.values()) == 254
    assert max(report.text_loss_ratio for report in reports.values()) <= 0.02
