"""Oracle-only proof that the Phase 8 fixture seeder is retry-safe."""

import os

import pytest

from scripts import seed_phase8_org_fixtures as seeder


pytestmark = pytest.mark.skipif(
    os.getenv("RUN_ORACLE_FIXTURE_TESTS", "false").lower() != "true",
    reason="set RUN_ORACLE_FIXTURE_TESTS=true with the local Oracle available",
)


def test_seeding_twice_leaves_the_same_fixture_row_counts():
    connection = seeder.connect_from_env()
    try:
        first = seeder.seed(connection, commit=False)
        second = seeder.seed(connection, commit=False)

        assert first == second
        assert second.departments == 3
        assert second.teams == 15
        assert second.users == 45
        assert second.primary_assignments == 15
    finally:
        connection.rollback()
        connection.close()
