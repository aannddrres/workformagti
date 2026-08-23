"""Audit 3, RTA-013: `python seed.py` must not be able to wipe production.

The script's no-argument branch deletes every user, article, audit row,
message and compliance record in the database ``DATABASE_URL`` points at, and
it used to ship inside the runtime image (`COPY . .`, no .dockerignore entry).
The whole failure mode was an operator with a shell in the wrong container
typing something that reads like an idempotent bootstrap.

These run the real script in a subprocess rather than importing it, because
what is being tested is what happens when a person types the command -- the
guard has to fire before ``seed_database()`` and before any DDL, and only a
real invocation proves that ordering.
"""

import os
import subprocess
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent

# Long, varied and not a placeholder: config.py refuses to import with the
# development default when APP_ENV=production, which would mask the guard
# under test with a different (also correct) refusal.
REAL_SECRET = "kJ8xQm2vTpZr7Nb4WgYc6HdLf9Es3AuKi1OjRt5Xn0PqMz8Vw2Yb7Gc4Hd6Lf1Ea"


def run_seed(*args, **env_overrides):
    env = {k: v for k, v in os.environ.items() if k not in {"APP_ENV", "SEED_CONFIRM_WIPE"}}
    env.update({"SECRET_KEY": REAL_SECRET, "COOKIE_SECURE": "true"})
    env.update({k: v for k, v in env_overrides.items() if v is not None})
    return subprocess.run(
        [sys.executable, "seed.py", *args],
        cwd=REPO_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )


def test_refuses_with_app_env_production():
    result = run_seed(APP_ENV="production")
    assert result.returncode != 0
    assert "REFUSED" in result.stdout + result.stderr


def test_refuses_when_app_env_is_unset():
    """The fail-safe case, and the one that actually matters.

    An operator in a container that never set APP_ENV is exactly the scenario
    the finding describes. APP_ENV defaults to production precisely so an
    unconfigured environment is treated as the dangerous one.
    """
    result = run_seed(APP_ENV=None)
    assert result.returncode != 0
    assert "REFUSED" in result.stdout + result.stderr


def test_refusal_names_the_three_ways_out():
    """A refusal that does not say what to do instead gets worked around."""
    # sys.exit(str) writes to stderr, which is where an operator will see it.
    output = run_seed(APP_ENV="production").stderr
    assert "APP_ENV=development" in output
    assert "seed.py org" in output
    assert "SEED_CONFIRM_WIPE" in output


def test_refuses_before_touching_the_database():
    """The guard runs before create_all, so a refused run issues no DDL.

    Pointed at a path that does not exist and could not be created: if the
    guard ran after create_all, SQLAlchemy would fail here with its own error
    instead of the refusal.
    """
    result = run_seed(
        APP_ENV="production",
        DATABASE_URL="sqlite:////nonexistent-directory-rta013/should-never-be-created.db",
    )
    assert "REFUSED" in result.stdout + result.stderr
    assert "unable to open database" not in (result.stdout + result.stderr).lower()
    assert not Path("/nonexistent-directory-rta013").exists()


@pytest.mark.parametrize("subcommand", ["org", "ORG"])
def test_org_seeder_is_never_blocked(subcommand):
    """`seed.py org` is idempotent and deletes nothing, so the guard must not
    stand in its way -- including in production, which is where it is useful."""
    result = run_seed(subcommand, APP_ENV="production")
    assert "REFUSED" not in result.stdout + result.stderr
