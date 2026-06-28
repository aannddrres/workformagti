"""
Database engine / session setup.

The connection string comes from config.Settings (env-driven), so switching from
SQLite to PostgreSQL is a configuration change, not a code change:

    # local dev (default)
    DATABASE_URL=sqlite:///./magti_portal.db

    # production
    DATABASE_URL=postgresql+psycopg2://magti:secret@db-host:5432/magti_portal

For schema migrations on PostgreSQL use Alembic rather than create_all():
    pip install alembic && alembic init alembic
"""
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker, declarative_base

from config import settings

SQLALCHEMY_DATABASE_URL = settings.DATABASE_URL

if settings.is_sqlite:
    # check_same_thread is a SQLite-only flag (needed for FastAPI's threadpool).
    # The bounded pool is intentional: it acts as a back-pressure valve against
    # SQLite's single-writer lock. NullPool let too many concurrent INSERTs hit
    # the writer and busy_timeout (30s) failed — keep the pool throttle here.
    engine = create_engine(
        SQLALCHEMY_DATABASE_URL,
        connect_args={"check_same_thread": False, "timeout": 30},
        pool_size=50,
        max_overflow=150,
        pool_timeout=30,
    )
    
    @event.listens_for(engine, "connect")
    def set_sqlite_pragma(dbapi_connection, connection_record):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=30000")
        cursor.execute("PRAGMA synchronous=NORMAL")
        cursor.close()
else:
    # PostgreSQL (and other server databases): use a real connection pool.
    # pool_pre_ping recycles connections dropped by the DB/idle-timeout so the
    # app survives a database restart without 500s.
    engine = create_engine(
        SQLALCHEMY_DATABASE_URL,
        pool_size=20,
        max_overflow=40,
        pool_timeout=120,
        pool_pre_ping=True,
        pool_recycle=1800,
    )

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_tbilisi_time():
    from datetime import datetime, timezone, timedelta
    return datetime.now(timezone(timedelta(hours=4))).replace(tzinfo=None)
