"""DEC-P06: the production startup guard, ported up to the Java side's level.

It used to compare SECRET_KEY against ONE exact literal and never look at the
database password. Both gaps were reachable with the values this repo itself
ships: ``.env.example`` carries ``CHANGE_ME`` inside ``DATABASE_URL`` and
``CHANGE_ME_TO_A_STRONG_PASSWORD`` for ``POSTGRES_PASSWORD``, and neither is
the literal the old check knew, so a deployment that edited the example file
without replacing those values started clean.

Exhaustive over the two lists rather than over the three checks: the checks
are independent ``if`` statements each reading its own value, so a truth table
across them would only restate the per-check tests. The combinatorial surface
is inside them -- ten placeholder markers and seven dev passwords -- and an
entry that rejects nothing looks exactly like one that works.

Pure: ``config`` imports only ``os`` and ``urllib.parse``, so these run
without the app's dependencies installed.
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

from config import (
    _KNOWN_DEV_DB_PASSWORDS,
    _MIN_SECRET_LENGTH,
    _PLACEHOLDER_MARKERS,
    database_url_password,
    is_known_dev_database_password,
    secret_key_problem,
)

# Long, varied, and not a placeholder -- what a real
# secrets.token_urlsafe(64) looks like.
_STRONG_SECRET = "kJ8xQm2vTpZr7Nb4WgYc6HdLf9Es3AuKi1OjRt5Xn0PqMz8Vw2Yb7Gc4Hd6Lf1Ea"


def test_a_real_generated_secret_is_accepted():
    """If this ever fails, no correct deployment can start."""
    assert secret_key_problem(_STRONG_SECRET) is None
    assert len(_STRONG_SECRET) >= _MIN_SECRET_LENGTH


@pytest.mark.parametrize("secret", [None, "", "   "])
def test_a_missing_secret_is_rejected(secret):
    assert secret_key_problem(secret) == "is not set"


def test_the_shipped_development_default_is_rejected():
    """The one case the old check did catch. Kept so the rewrite cannot lose
    it while gaining the others."""
    assert secret_key_problem(
        "super-secret-temporary-key-for-local-development"
    ) == "is still a placeholder value"


@pytest.mark.parametrize("marker", _PLACEHOLDER_MARKERS)
def test_every_placeholder_marker_actually_rejects_a_secret(marker):
    """Read off the module's own tuple rather than copied here: a duplicated
    list drifts the moment somebody adds a marker, and drifts toward testing
    less than the code does.

    The marker is appended to an otherwise-strong secret, so the only rule
    left that can reject it is the marker itself.
    """
    assert secret_key_problem(_STRONG_SECRET + marker) == "is still a placeholder value"


def test_a_short_secret_is_rejected_and_says_how_short():
    problem = secret_key_problem("Qp7zR2vLm9Ks4TxW")
    assert problem == f"is only 16 characters (minimum {_MIN_SECRET_LENGTH})"


def test_a_long_but_repetitive_secret_is_rejected():
    """Long enough to pass the length rule, almost no entropy."""
    assert secret_key_problem("ab" * 30) == (
        "has too few distinct characters to be a real random value"
    )


@pytest.mark.parametrize("password", _KNOWN_DEV_DB_PASSWORDS)
def test_every_known_dev_database_password_is_rejected(password):
    assert is_known_dev_database_password(password)


@pytest.mark.parametrize("password", [None, "", "   "])
def test_an_absent_database_password_is_not_the_guards_problem(password):
    """SQLite has none, and a deployment may supply credentials another way
    (.pgpass, an IAM token, socket peer trust). The driver reports a genuinely
    missing password precisely; guessing here would block a working setup to
    catch nothing."""
    assert not is_known_dev_database_password(password)


def test_a_real_database_password_is_accepted():
    assert not is_known_dev_database_password("Qp7#zR2vLm9!Ks4T")


def test_the_password_shipped_in_the_example_env_is_rejected():
    """.env.example line 29 and line 33, which is how this reaches a real
    deployment: somebody copies the file and edits only what stops the app
    from booting."""
    from_url = database_url_password(
        "postgresql+psycopg2://magti:CHANGE_ME@localhost:5432/magti_portal"
    )
    assert from_url == "CHANGE_ME"
    assert is_known_dev_database_password(from_url)
    assert is_known_dev_database_password("CHANGE_ME_TO_A_STRONG_PASSWORD")


def test_the_password_is_read_out_of_the_url_the_app_connects_with():
    assert database_url_password(
        "postgresql+psycopg2://appuser:Qp7zR2vLm9Ks4T@db:5432/magti_portal"
    ) == "Qp7zR2vLm9Ks4T"
    # SQLite carries no credentials at all.
    assert database_url_password("sqlite:///./magti_portal.db") is None
    # An unparseable URL is the driver's problem to diagnose, not this one's.
    assert database_url_password("not a url at all") is None


# ── The guard itself, not just the rules it is built from ──────────────


def _import_config_with(**env):
    """Import config.py in a fresh interpreter under the given environment.

    A subprocess rather than importlib.reload: the guard runs at import time
    and raises, and reloading a module other tests already hold a reference to
    would leave them looking at a half-initialised one. config.py imports only
    os and urllib.parse, so a bare interpreter is enough.
    """
    child = dict(os.environ)
    child.pop("APP_ENV", None)
    child.pop("SECRET_KEY", None)
    child.pop("DATABASE_URL", None)
    child.pop("COOKIE_SECURE", None)
    child.update({k: v for k, v in env.items() if v is not None})
    return subprocess.run(
        [sys.executable, "-c", "import config"],
        cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        env=child,
        capture_output=True,
        text=True,
    )


_PRODUCTION_OK = {
    "APP_ENV": "production",
    "SECRET_KEY": _STRONG_SECRET,
    "DATABASE_URL": "postgresql+psycopg2://appuser:Qp7zR2vLm9Ks4T@db:5432/magti_portal",
    "COOKIE_SECURE": "true",
}


def test_a_correctly_configured_production_import_succeeds():
    """First, because everything below is only meaningful if this passes: a
    guard that refuses every configuration is not a guard."""
    result = _import_config_with(**_PRODUCTION_OK)
    assert result.returncode == 0, result.stderr


@pytest.mark.parametrize(
    "override, expected",
    [
        ({"SECRET_KEY": "super-secret-temporary-key-for-local-development"},
         "SECRET_KEY is still a placeholder value"),
        ({"SECRET_KEY": "Qp7zR2vLm9Ks4TxW"}, "SECRET_KEY is only 16 characters"),
        ({"SECRET_KEY": "ab" * 30}, "too few distinct characters"),
        ({"SECRET_KEY": ""}, "SECRET_KEY is not set"),
        ({"DATABASE_URL": "postgresql+psycopg2://magti:CHANGE_ME@db:5432/magti_portal"},
         "DATABASE_URL still carries a shipped development password"),
        ({"COOKIE_SECURE": "false"}, "COOKIE_SECURE=false"),
    ],
)
def test_each_problem_actually_stops_the_boot(override, expected):
    """Proves the pure functions above are wired into the guard, and that the
    operator is told which rule they tripped."""
    result = _import_config_with(**{**_PRODUCTION_OK, **override})

    assert result.returncode != 0, f"a production boot with {override} was allowed"
    assert expected in result.stderr, result.stderr


def test_a_development_import_is_never_checked():
    """The guard is scoped to production on purpose; widening it would break
    every developer's machine."""
    result = _import_config_with(
        APP_ENV="development",
        SECRET_KEY="super-secret-temporary-key-for-local-development",
        DATABASE_URL="postgresql+psycopg2://magti:CHANGE_ME@db:5432/magti_portal",
        COOKIE_SECURE="false",
    )
    assert result.returncode == 0, result.stderr
