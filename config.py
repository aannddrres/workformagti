"""
Centralised application configuration.

Every environment-specific value is read from an environment variable with a
safe local-development default, so the same codebase runs unchanged on a laptop
(SQLite) and in production (PostgreSQL behind HTTPS). In production you MUST set
at least SECRET_KEY and DATABASE_URL.

A local .env file is loaded automatically if python-dotenv is installed
(see requirements.txt / .env.example).
"""
import os

try:
    # Optional: load a local .env so `uvicorn main:app` picks up overrides.
    from dotenv import load_dotenv

    load_dotenv()
except Exception:  # pragma: no cover - dotenv is optional in dev
    pass


def _csv_env(name: str, default: str) -> list[str]:
    raw = os.getenv(name, default)
    return [item.strip() for item in raw.split(",") if item.strip()]


# The environments that are NOT production. Everything else is.
#
# Deliberately the complement of what you would expect. Listing the production
# spellings instead ("production", "prod", "prd", ...) can never be finished,
# and every name missing from it fails OPEN: APP_ENV=prod and the typo
# APP_ENV=produciton both stop being production, and on this codebase that
# means security.py:57 populates TEST_EMAILS — six accounts, admin@magti.ge
# among them, where any password is accepted — and security.py:198 will
# JIT-provision them. Unlike the Java port there is no second
# ALLOW_DEV_LOGIN switch behind it, so one wrong character is the whole
# distance between a deployment and unauthenticated admin access.
#
# Listed this way an unrecognised value fails SAFE: the startup guard below
# runs, and the bypass stays empty.
#
# Only local-machine names are here. "staging", "qa", "uat", "sandbox" and
# "preprod" are deployed environments other people can reach, so they get the
# production posture — a change from the old behaviour, where every string
# except "production" enabled the bypass.
_DEVELOPMENT_ENVIRONMENTS = frozenset({"development", "dev", "local", "test"})


def is_development_environment(app_env: str | None) -> bool:
    """True only when app_env explicitly names a local development environment.

    Whitespace and casing are forgiven because they are never intent — a
    trailing space is what a .env file and docker compose both preserve, and
    APP_ENV=production with one used to be a development environment. The word
    itself is not forgiven, because it always is intent.
    """
    return (app_env or "").strip().lower() in _DEVELOPMENT_ENVIRONMENTS


def resolve_log_level(app_env: str, explicit: str | None) -> str:
    """Explicit LOG_LEVEL wins; otherwise production is quiet (INFO) and
    development verbose (DEBUG). Pure function so tests can pin the contract
    without re-importing the module under a patched environment."""
    if explicit and explicit.strip():
        return explicit.strip().upper()
    return "DEBUG" if is_development_environment(app_env) else "INFO"


class Settings:
    # ── Environment ───────────────────────────────────────────────────
    # Anything that is not a named development environment disables the
    # developer-only conveniences (mock AD allowlist, verbose tracebacks) --
    # see _DEVELOPMENT_ENVIRONMENTS for why that list is the one written out
    # rather than the production spellings.
    #
    # Stripped as well as lowercased: "production " with a trailing space used
    # to be a development environment, and .env files and docker compose both
    # preserve one.
    #
    # The "development" default is only reached by a bare local run: the
    # Dockerfile sets APP_ENV=production and docker-compose.yml falls back to
    # ${APP_ENV:-production}, so no deployment path relies on it.
    APP_ENV: str = os.getenv("APP_ENV", "development").strip().lower()

    # ── Security / JWT ────────────────────────────────────────────────
    # NEVER ship the development default to production. Generate a strong key:
    #   python -c "import secrets; print(secrets.token_urlsafe(64))"
    SECRET_KEY: str = os.getenv(
        "SECRET_KEY", "super-secret-temporary-key-for-local-development"
    )
    ALGORITHM: str = os.getenv("JWT_ALGORITHM", "HS256")
    ACCESS_TOKEN_EXPIRE_MINUTES: int = int(
        os.getenv("ACCESS_TOKEN_EXPIRE_MINUTES", "60")
    )

    # Cookie-based auth (defence-in-depth alternative to localStorage tokens).
    # In production (HTTPS, same-origin) set COOKIE_SECURE=true.
    COOKIE_SECURE: bool = os.getenv("COOKIE_SECURE", "false").lower() == "true"
    COOKIE_SAMESITE: str = os.getenv("COOKIE_SAMESITE", "lax")

    # ── Database ──────────────────────────────────────────────────────
    # Local default: SQLite. Production example:
    #   postgresql+psycopg2://magti:secret@db-host:5432/magti_portal
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./magti_portal.db")

    # ── CORS ──────────────────────────────────────────────────────────
    CORS_ORIGINS: list[str] = _csv_env(
        "CORS_ORIGINS", "http://127.0.0.1:5500,http://localhost:5500"
    )

    # ── File uploads ──────────────────────────────────────────────────
    UPLOAD_DIR: str = os.getenv("UPLOAD_DIR", "uploads")
    MAX_UPLOAD_SIZE_BYTES: int = int(
        os.getenv("MAX_UPLOAD_SIZE_BYTES", str(10 * 1024 * 1024))  # 10 MB
    )
    # Canonical extension per allowed MIME type. The stored extension is derived
    # from this map (never from the client-supplied filename), and any type not
    # listed here is rejected. Active/executable content (.html, .svg, .js, .php)
    # is intentionally excluded to prevent stored-XSS / arbitrary execution.
    ALLOWED_UPLOAD_TYPES: dict[str, str] = {
        "application/pdf": ".pdf",
        "image/png": ".png",
        "image/jpeg": ".jpg",
        "image/gif": ".gif",
        "image/webp": ".webp",
        "text/plain": ".txt",
        "application/msword": ".doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
        "application/vnd.ms-excel": ".xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx",
        "video/mp4": ".mp4",
    }

    # ── Logging ───────────────────────────────────────────────────────
    LOG_LEVEL: str = resolve_log_level(
        os.getenv("APP_ENV", "development"), os.getenv("LOG_LEVEL")
    )  # resolve_log_level strips/lowercases via is_development_environment
    # SQL echo is opt-in ONLY (dev chaos-testing aid): at DEBUG it logs every
    # query from every worker and rotates real errors out of the log in hours.
    LOG_SQL: bool = os.getenv("LOG_SQL", "false").lower() == "true"

    # ── Data retention ────────────────────────────────────────────────
    # Audit-log and article-view-log rows older than this are exported to a
    # JSON archive under archives/ and purged from the DB (see retention.py,
    # run daily by the backup container).
    AUDIT_RETENTION_DAYS: int = int(os.getenv("AUDIT_RETENTION_DAYS", "180"))

    @property
    def is_sqlite(self) -> bool:
        return self.DATABASE_URL.startswith("sqlite")

    @property
    def is_production(self) -> bool:
        return not is_development_environment(self.APP_ENV)


settings = Settings()

if settings.is_production:
    # Fail loud at startup rather than silently shipping a dev secret or a
    # cookie sent over plain HTTP — both are the kind of misconfiguration
    # that's easy to miss in a one-person deployment and expensive once live.
    if settings.SECRET_KEY == "super-secret-temporary-key-for-local-development":
        raise RuntimeError(
            "SECRET_KEY is still the development default with APP_ENV=production. "
            'Generate one: python -c "import secrets; print(secrets.token_urlsafe(64))"'
        )
    if not settings.COOKIE_SECURE:
        raise RuntimeError(
            "COOKIE_SECURE=false with APP_ENV=production — the auth cookie would be "
            "sent over plain HTTP. Set COOKIE_SECURE=true (requires HTTPS)."
        )
