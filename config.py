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


def resolve_log_level(app_env: str, explicit: str | None) -> str:
    """Explicit LOG_LEVEL wins; otherwise production is quiet (INFO) and
    development verbose (DEBUG). Pure function so tests can pin the contract
    without re-importing the module under a patched environment."""
    if explicit and explicit.strip():
        return explicit.strip().upper()
    return "INFO" if app_env.lower() == "production" else "DEBUG"


class Settings:
    # ── Environment ───────────────────────────────────────────────────
    # "production" disables developer-only conveniences (mock AD allowlist,
    # verbose tracebacks). Anything else is treated as a development env.
    APP_ENV: str = os.getenv("APP_ENV", "development").lower()

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
    )
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
        return self.APP_ENV == "production"


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
