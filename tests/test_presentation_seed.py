from __future__ import annotations

import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
PRESENTATION_SCRIPTS = ROOT / "scripts" / "presentation"
sys.path.insert(0, str(PRESENTATION_SCRIPTS))

from common import (  # noqa: E402
    EXPECTED_ARTICLES,
    EXPECTED_ARTICLE_HISTORY,
    EXPECTED_ASSETS,
    EXPECTED_ORACLE_CONTEXT,
    PresentationSafetyError,
    SanitizationLossError,
    SanitizationStats,
    assert_expected_inventory,
    assert_local_environment,
    build_source_inventory,
    local_upload_filename,
    normalize_department,
    normalize_search_entity_type,
    open_source_database,
    path_is_on_read_only_mount,
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

    with pytest.raises(PresentationSafetyError, match="Unknown department"):
        normalize_department("Support")


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


def test_local_environment_guard_is_exact() -> None:
    assert_local_environment(
        app_env="development",
        dsn="oracle:1521/XEPDB1",
        user="magti_app",
        confirmation="LOCAL_ONLY_MAGTI_PRESENTATION_V1",
    )
    with pytest.raises(PresentationSafetyError, match="APP_ENV"):
        assert_local_environment(
            app_env="production",
            dsn="oracle:1521/XEPDB1",
            user="magti_app",
            confirmation="LOCAL_ONLY_MAGTI_PRESENTATION_V1",
        )


def test_oracle_runtime_context_guard_matches_the_local_xepdb1_service() -> None:
    assert EXPECTED_ORACLE_CONTEXT == ("MAGTI_APP", "XEPDB1", "XEPDB1")


def test_search_entity_types_match_the_spring_contract() -> None:
    assert normalize_search_entity_type("article") == "ARTICLE"
    assert normalize_search_entity_type("NEWS") == "NEWS"
    assert normalize_search_entity_type("video") == "VIDEO"
    with pytest.raises(PresentationSafetyError, match="Unknown search entity type"):
        normalize_search_entity_type("document")
    with pytest.raises(PresentationSafetyError, match="DSN"):
        assert_local_environment(
            app_env="development",
            dsn="localhost:1521/XEPDB1",
            user="magti_app",
            confirmation="LOCAL_ONLY_MAGTI_PRESENTATION_V1",
        )


def test_read_only_mount_detection_uses_longest_covering_mount() -> None:
    mountinfo = "\n".join(
        (
            "10 1 0:1 / / rw,relatime - overlay overlay rw",
            "11 10 0:2 / /source ro,relatime - ext4 /dev/sda ro",
            "12 11 0:3 / /source/uploads ro,relatime - ext4 /dev/sdb ro",
        )
    )
    assert path_is_on_read_only_mount(Path("/source/magti_portal.db"), mountinfo)
    assert path_is_on_read_only_mount(Path("/source/uploads/image.png"), mountinfo)


def test_trigrams_are_distinct_and_unicode_safe() -> None:
    assert trigrams("აბგაბგ") == ["აბგ", "ბგა", "გაბ"]
    assert trigrams("ab") == []


@pytest.mark.skipif(not SOURCE_DB.is_file() or not SOURCE_UPLOADS.is_dir(), reason="local presentation source is absent")
def test_approved_source_inventory_matches_manifest() -> None:
    inventory = build_source_inventory(SOURCE_DB, SOURCE_UPLOADS)
    assert_expected_inventory(inventory)
    assert inventory.articles == EXPECTED_ARTICLES
    assert inventory.article_history == EXPECTED_ARTICLE_HISTORY
    assert inventory.assets == EXPECTED_ASSETS


@pytest.mark.skipif(not SOURCE_DB.is_file() or not SOURCE_UPLOADS.is_dir(), reason="local presentation source is absent")
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


def test_compose_and_reset_are_scoped_to_the_presentation_project() -> None:
    compose = (ROOT / "docker-compose.presentation.yml").read_text(encoding="utf-8")
    wrapper = (ROOT / "presentation.ps1").read_text(encoding="utf-8")
    frontend_dockerfile = (ROOT / "angular-frontend" / "Dockerfile").read_text(encoding="utf-8")

    assert "name: magti-portal-presentation" in compose
    assert "127.0.0.1:8081:8080" in compose
    assert "ALLOW_DEV_LOGIN: \"false\"" in compose
    assert "magti-portal-presentation-oracle-data" in compose
    assert "./magti_portal.db:/source/magti_portal.db:ro" in compose
    assert "./uploads:/source/uploads:ro" in compose
    assert "$PresentationVolume = 'magti-portal-presentation-oracle-data'" in wrapper
    assert "Docker\\Docker\\resources\\bin\\docker.exe" in wrapper
    assert '$env:Path = "$dockerDesktopBin;$env:Path"' in wrapper
    assert "& $DockerCommand compose" in wrapper
    assert "down --volumes --remove-orphans" in wrapper
    assert "http://127.0.0.1:8080/" in frontend_dockerfile


def test_local_qa_profiles_are_scoped_and_reported() -> None:
    compose = (ROOT / "docker-compose.presentation.yml").read_text(encoding="utf-8")
    wrapper = (ROOT / "presentation.ps1").read_text(encoding="utf-8")
    qa_runner = (ROOT / "scripts" / "presentation" / "qa_regression.py").read_text(encoding="utf-8")

    assert "[ValidateSet('quick', 'regression')]" in wrapper
    assert "Invoke-TestProfile $TestProfile" in wrapper
    assert "Java Oracle integration tests in isolated QA schema" in wrapper
    assert "Selected Playwright presentation persona regression" in wrapper
    assert "Regression failed; presentation data is preserved for investigation." in wrapper
    assert "Restore and verify clean presentation baseline" in wrapper
    assert "tests\\$runId" in wrapper
    assert '\"127.0.0.1:1523:1521\"' in compose
    assert "qa_schema_create.sql" in wrapper
    assert "qa_schema_drop.sql" in wrapper
    assert 'entrypoint: ["python", "/app/qa_regression.py"]' in compose
    assert "ACCESS_CASES" in qa_runner
    assert '("manager@magti.ge", "system.audit", "DENY", 200, 403)' in qa_runner
    assert '("info@magti.ge", "system.audit", "ALLOW", 403, 200)' in qa_runner
    assert "_assert_audit_chain" in qa_runner
