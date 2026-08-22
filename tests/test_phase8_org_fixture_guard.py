"""Always-on tests for the production boundary around Phase 8 fixtures."""

import pytest

from scripts.seed_phase8_org_fixtures import _assert_local_target


@pytest.fixture(autouse=True)
def clear_environment_markers(monkeypatch):
    for name in ("APP_ENV", "ENVIRONMENT", "SPRING_PROFILES_ACTIVE"):
        monkeypatch.delenv(name, raising=False)


@pytest.mark.parametrize(
    "dsn",
    (
        "localhost:1521/orclpdb1",
        "127.0.0.1:1521/x",
        "[::1]:1521/x",
    ),
)
def test_loopback_oracle_targets_are_allowed(dsn):
    _assert_local_target(dsn)


@pytest.mark.parametrize(
    "dsn",
    (
        "prod-db.magti.ge:1521/PROD",
        "jdbc:oracle:thin:@//prod:1521/X",
        "PRODDB",
    ),
)
def test_non_local_and_ambiguous_oracle_targets_fail_closed(dsn):
    with pytest.raises(RuntimeError, match="local-only"):
        _assert_local_target(dsn)


def test_production_environment_refuses_even_a_local_dsn(monkeypatch):
    monkeypatch.setenv("APP_ENV", "production")

    with pytest.raises(RuntimeError, match="production"):
        _assert_local_target("localhost:1521/orclpdb1")


def test_staging_spring_profile_refuses_even_a_local_dsn(monkeypatch):
    monkeypatch.setenv("SPRING_PROFILES_ACTIVE", "oracle,staging")

    with pytest.raises(RuntimeError, match="staging"):
        _assert_local_target("localhost:1521/orclpdb1")
