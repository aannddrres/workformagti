"""DEC-P04 / DEC-P05, ported from the Java side.

``is_production`` used to be ``self.APP_ENV == "production"`` against a value
that was lowercased but not stripped. Every other value was therefore a
development environment, and on this codebase that is not a cosmetic
difference: ``security.py:57`` populates ``TEST_EMAILS`` when
``not is_production`` -- six accounts, ``admin@magti.ge`` among them, where
any password is accepted -- and ``security.py:198`` JIT-provisions them on
first use. The Java port at least required a second ``ALLOW_DEV_LOGIN``
switch on top; here there is none, so one wrong character in APP_ENV is the
entire distance between a deployment and unauthenticated admin access.

Two shapes of wrong character:

* ``APP_ENV=production `` with a trailing space -- which .env files and
  docker compose both preserve (DEC-P04);
* ``APP_ENV=prod``, or the typo ``produciton`` (DEC-P05).

The fix lists the development environments instead of the production ones, so
an unrecognised value fails safe. These tests are pure -- ``config`` imports
only ``os`` -- so they run without the app's dependencies installed.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

from config import Settings, is_development_environment, resolve_log_level


def _settings_with(app_env):
    """A Settings whose APP_ENV is the given value, without re-importing the
    module under a patched environment (APP_ENV is read at class-definition
    time, so monkeypatching os.environ afterwards would change nothing)."""
    settings = Settings()
    settings.APP_ENV = app_env
    return settings


@pytest.mark.parametrize(
    "app_env",
    ["development", "dev", "local", "test", "DEVELOPMENT", " dev ", "\tLocal\n"],
)
def test_named_development_environments_are_not_production(app_env):
    """The insecure posture stays reachable, and only by naming it."""
    assert is_development_environment(app_env)
    assert not _settings_with(app_env).is_production


@pytest.mark.parametrize(
    "app_env",
    [
        "production", "PRODUCTION", "Production",
        # DEC-P04: whitespace is never intent.
        " production", "production ", "  production  ", "\tproduction\n",
        # DEC-P05: a shorthand or a typo must not be a way into the bypass.
        "prod", "prd", "live", "produciton", "prodcution", "developement",
        # Deployed environments other people can reach.
        "staging", "qa", "uat", "sandbox", "preprod",
        "anything-at-all",
        # Set but empty: a variable somebody meant to fill in and did not.
        "", "   ",
    ],
)
def test_everything_else_is_production(app_env):
    assert not is_development_environment(app_env)
    assert _settings_with(app_env).is_production


def test_none_fails_safe_to_production():
    """Not reachable through Settings -- ``os.getenv`` supplies the default
    before the value ever gets here -- but the helper is public, and None is
    the one input where a wrong answer inverts the safe direction."""
    assert not is_development_environment(None)
    assert _settings_with(None).is_production


def test_a_missing_app_env_uses_the_development_default(monkeypatch):
    """Deliberate, and only reachable by a bare local run: the Dockerfile sets
    APP_ENV=production and docker-compose.yml falls back to
    ${APP_ENV:-production}, so no deployment path depends on this default.

    Asserted through the same expression config.py uses, so a change to the
    default lands here rather than only in review."""
    monkeypatch.delenv("APP_ENV", raising=False)
    assert is_development_environment(os.getenv("APP_ENV", "development"))


def test_log_level_follows_the_same_rule():
    """One definition of "is this production", not two that can drift."""
    assert resolve_log_level("production", None) == "INFO"
    assert resolve_log_level("production ", None) == "INFO"
    assert resolve_log_level("prod", None) == "INFO"
    assert resolve_log_level("staging", None) == "INFO"
    assert resolve_log_level("development", None) == "DEBUG"
    assert resolve_log_level(" DEV ", None) == "DEBUG"
    # An explicit LOG_LEVEL still wins over both.
    assert resolve_log_level("production", "debug") == "DEBUG"
    assert resolve_log_level("development", "warning") == "WARNING"
