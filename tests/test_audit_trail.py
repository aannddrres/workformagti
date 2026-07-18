"""End-to-end proof of the revived auto-audit chain: a real HTTP article edit
(bearer token, no dependency overrides) must produce an AuditLog UPDATE row
attributed to the JWT holder, carrying the field-level old/new diff.

This exercises the full path: actor_context_middleware (decodes the token,
sets the ContextVar) -> ORM flush -> audit_trail after_update listener
(resolves the actor id on the flush connection, writes the row).
"""
import json
import os
import sys

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import engine
from main import app as monolith_app
import models
from tests.factories import make_article, make_user

models.Base.metadata.create_all(bind=engine)


def test_http_article_edit_writes_attributed_diff(db_session):
    monolith_app.dependency_overrides.clear()

    article = None
    with TestClient(monolith_app) as tc:
        # Dev bypass: seeded admin@magti.ge authenticates regardless of
        # password outside production — gives us a REAL token.
        login = tc.post(
            "/api/auth/login",
            json={"email": "admin@magti.ge", "password": "password"},
        )
        assert login.status_code == 200, login.text
        token = login.json()["access_token"]

        admin_row = db_session.query(models.User).filter(
            models.User.email == "admin@magti.ge"
        ).first()
        assert admin_row is not None

        article = make_article(db_session, author=admin_row, title="AuditTrail E2E Article")

        res = tc.put(
            f"/api/articles/{article.id}",
            json={
                "title": "AuditTrail E2E Article RENAMED",
                "content": article.content,
                "category_id": article.category_id,
                "target_departments": ["All"],
                "status": "published",
                "is_draft": False,
            },
            headers={"Authorization": f"Bearer {token}"},
        )
        assert res.status_code == 200, res.text

    db_session.expire_all()
    log = (
        db_session.query(models.AuditLog)
        .filter(
            models.AuditLog.item_type == "article",
            models.AuditLog.item_id == article.id,
            models.AuditLog.action == "UPDATE",
        )
        .order_by(models.AuditLog.id.desc())
        .first()
    )
    assert log is not None, "auto-audit row missing for HTTP article edit"
    assert log.admin_id == admin_row.id, "audit row must be attributed to the JWT holder"
    assert log.category == "CONTENT"

    details = json.loads(log.details)
    assert details["changed"]["title"]["old"] == "AuditTrail E2E Article"
    assert details["changed"]["title"]["new"] == "AuditTrail E2E Article RENAMED"
    # The version bump rides along in the same diff.
    assert details["changed"]["version"]["new"] == 2

    # Cleanup: this test's audit rows, history snapshot, and the article.
    db_session.query(models.AuditLog).filter(
        models.AuditLog.item_type == "article",
        models.AuditLog.item_id == article.id,
    ).delete()
    db_session.query(models.ArticleHistory).filter(
        models.ArticleHistory.article_id == article.id
    ).delete()
    art = db_session.query(models.Article).filter(models.Article.id == article.id).first()
    if art:
        db_session.delete(art)
    db_session.commit()


def test_audit_list_survives_deleted_actor_and_resolves_item_names(db_session):
    """The audit list must preserve the actor's real name even after account
    deletion — immutable snapshots captured at INSERT time ensure no data
    loss. Item names must also resolve correctly.
    """
    import uuid

    import audit_trail

    actor = make_user(db_session, email="factory_audit_ghost@magti.ge", role="admin")
    viewer = make_user(db_session, email="factory_audit_viewer@magti.ge", role="admin")
    unique = f"Audit Ghost Cat {uuid.uuid4().hex[:8]}"
    actor_name = actor.name  # Capture before deletion

    ctx = audit_trail.current_actor_email.set(actor.email)
    try:
        category = models.Category(name=unique, is_active=True)
        db_session.add(category)
        db_session.commit()
        db_session.refresh(category)
    finally:
        audit_trail.current_actor_email.reset(ctx)

    actor_id = actor.id
    # Simulate a later account deletion — the audit row must survive it.
    db_session.delete(actor)
    db_session.commit()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: viewer
    monolith_app.dependency_overrides[security.get_current_system_admin_user] = lambda: viewer
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get(f"/api/audit-logs?user_id={actor_id}&action=CREATE")
            assert res.status_code == 200, res.text
            rows = [r for r in res.json() if r["item_id"] == category.id and r["item_type"] == "category"]
            assert rows, "audit row of a deleted actor must remain visible"
            # Immutable snapshot: the actor's REAL name is preserved, not a
            # generic 'წაშლილი მომხმარებელი' fallback.
            assert rows[0]["admin_name"] == actor_name, (
                "admin_name_snapshot must preserve the real name after deletion"
            )
            assert rows[0]["item_name"] == unique, "category item_name must resolve"
    finally:
        monolith_app.dependency_overrides.clear()

    db_session.query(models.AuditLog).filter(models.AuditLog.admin_id == actor_id).delete()
    db_session.delete(category)
    db_session.delete(viewer)
    db_session.commit()


def test_snapshot_immutability_after_user_deletion(db_session):
    """Verifies the core immutability guarantee: an audit log row's
    admin_name_snapshot and item_name_snapshot survive deletion of both
    the actor and the target item.
    """
    import audit_trail

    actor = make_user(db_session, email="factory_immutable_actor@magti.ge", role="admin")
    actor_name = actor.name
    actor_email = actor.email

    # Create a news item (sets the ContextVar so the ORM listener captures it)
    ctx = audit_trail.current_actor_email.set(actor.email)
    try:
        news = models.News(title="Immutability Test News", content="Test content",
                           target_department="All")
        db_session.add(news)
        db_session.commit()
        db_session.refresh(news)
    finally:
        audit_trail.current_actor_email.reset(ctx)

    news_id = news.id
    actor_id = actor.id

    # Verify the snapshot was captured at INSERT time
    log = db_session.query(models.AuditLog).filter(
        models.AuditLog.item_type == "news",
        models.AuditLog.item_id == news_id,
        models.AuditLog.action == "CREATE",
    ).first()
    assert log is not None, "ORM auto-audit must write a CREATE row"
    assert log.admin_name_snapshot == actor_name
    assert log.admin_email_snapshot == actor_email
    assert log.item_name_snapshot == "Immutability Test News"

    # Delete both the actor AND the news item
    db_session.delete(news)
    db_session.commit()
    db_session.delete(actor)
    db_session.commit()

    # Refresh and verify: snapshots MUST survive deletion
    db_session.expire_all()
    log = db_session.query(models.AuditLog).filter(models.AuditLog.id == log.id).first()
    assert log is not None, "audit row must not be cascade-deleted"
    assert log.admin_name_snapshot == actor_name, (
        "admin_name_snapshot must be immutable after user deletion"
    )
    assert log.admin_email_snapshot == actor_email, (
        "admin_email_snapshot must be immutable after user deletion"
    )
    assert log.item_name_snapshot == "Immutability Test News", (
        "item_name_snapshot must be immutable after content deletion"
    )

    # Cleanup
    db_session.query(models.AuditLog).filter(models.AuditLog.admin_id == actor_id).delete()
    db_session.commit()


def test_chain_health_access_and_sqlite_degradation():
    """GET /api/audit-logs/chain-health on SQLite: degrades to 200/'unavailable'
    (never a 5xx — the dashboard fires it passively on every mount, so an
    error status would put a guaranteed red console line on every dev load),
    clamps n to [1, 500], and enforces the same system:audit permission as
    the list endpoints (admin bypass / seeded content_admin / operator 403).
    """
    monolith_app.dependency_overrides.clear()
    with TestClient(monolith_app) as tc:
        def login(email):
            # Dev bypass: TEST_EMAILS authenticate regardless of password.
            r = tc.post("/api/auth/login", json={"email": email, "password": "x"})
            assert r.status_code == 200, r.text
            return {"Authorization": "Bearer " + r.json()["access_token"]}

        admin = login("admin@magti.ge")
        res = tc.get("/api/audit-logs/chain-health", headers=admin)
        assert res.status_code == 200, res.text
        assert res.json()["status"] == "unavailable"
        assert res.json()["checked"] == 0

        # n clamps before the dialect gate, so it's assertable on SQLite too.
        assert tc.get("/api/audit-logs/chain-health?n=99999", headers=admin).json()["window"] == 500
        assert tc.get("/api/audit-logs/chain-health?n=0", headers=admin).json()["window"] == 1

        # content_admin holds the seeded system:audit binding (conftest runs
        # migrate.ensure_system_audit_permission_seeded).
        content = login("content@magti.ge")
        assert tc.get("/api/audit-logs/chain-health", headers=content).status_code == 200

        # operator holds no such permission.
        operator = login("tech@magti.ge")
        assert tc.get("/api/audit-logs/chain-health", headers=operator).status_code == 403


def test_manager_sees_only_own_department_audit_logs(db_session):
    """A manager holds the same system:audit permission as content_admin
    (migrate.py's ensure_system_audit_permission_seeded now binds both), but
    main.py's _audit_scope_department hard-pins their /api/audit-logs results
    to their own department — "own group only, nothing more". Export,
    per-row verify, and chain-health stay blocked outright for a manager (no
    meaningful per-department slice of one global hash chain, and export is
    bulk egress this role shouldn't have).

    Only 2 real /api/auth/login calls (manager@, content@) — the two
    operator accounts are created directly via make_user() + a raw AuditLog
    insert instead of logging in as them, since /api/auth/login is rate
    limited (10/minute) and shared across the whole pytest session.
    """
    import uuid

    monolith_app.dependency_overrides.clear()

    unique = uuid.uuid4().hex[:8]
    # JIT-provisioned manager@magti.ge (security.py:178-179) lands in
    # department "Support" — match that exactly for the in-group user.
    in_group = make_user(db_session, email=f"scope_test_ingroup_{unique}@magti.ge",
                          role="operator", department="Support")
    out_group = make_user(db_session, email=f"scope_test_outgroup_{unique}@magti.ge",
                           role="operator", department="Informational")
    db_session.add(models.AuditLog(admin_id=in_group.id, action="LOGIN", item_type="user", item_id=in_group.id))
    db_session.add(models.AuditLog(admin_id=out_group.id, action="LOGIN", item_type="user", item_id=out_group.id))
    db_session.commit()

    with TestClient(monolith_app) as tc:
        def login(email):
            # Dev bypass: TEST_EMAILS authenticate regardless of password.
            r = tc.post("/api/auth/login", json={"email": email, "password": "x"})
            assert r.status_code == 200, r.text
            return {"Authorization": "Bearer " + r.json()["access_token"]}

        manager_headers = login("manager@magti.ge")
        assert tc.get("/api/audit-logs", headers=manager_headers).status_code == 200

        res = tc.get("/api/audit-logs?limit=200&action=LOGIN", headers=manager_headers)
        assert res.status_code == 200, res.text
        visible_admin_ids = {row["admin_id"] for row in res.json()}
        assert in_group.id in visible_admin_ids, "manager must see their own department's rows"
        assert out_group.id not in visible_admin_ids, "manager must NOT see another department's rows"

        # Explicit out-of-group user_id: empty result, not 403/404 — the
        # department filter is AND'd in, it doesn't leak existence via errors.
        res = tc.get(f"/api/audit-logs?user_id={out_group.id}", headers=manager_headers)
        assert res.status_code == 200
        assert res.json() == []

        # content_admin stays unrestricted — scoping must not leak onto the
        # existing unscoped tier.
        content_headers = login("content@magti.ge")
        res = tc.get("/api/audit-logs?limit=200&action=LOGIN", headers=content_headers)
        assert res.status_code == 200, res.text
        admin_ids = {row["admin_id"] for row in res.json()}
        assert in_group.id in admin_ids and out_group.id in admin_ids
        any_log_id = res.json()[0]["id"]

        # Export / verify / chain-health: blocked outright for a manager,
        # even though the same PERM_SYSTEM_AUDIT dependency admits them.
        assert tc.get("/api/audit-logs/export", headers=manager_headers).status_code == 403
        assert tc.get("/api/audit-logs/chain-health", headers=manager_headers).status_code == 403
        assert tc.get(f"/api/audit-logs/{any_log_id}/verify", headers=manager_headers).status_code == 403

    db_session.query(models.AuditLog).filter(
        models.AuditLog.admin_id.in_([in_group.id, out_group.id])
    ).delete(synchronize_session=False)
    db_session.delete(in_group)
    db_session.delete(out_group)
    db_session.commit()


_PG_TEST_URL = os.environ.get("MAGTI_PG_TEST_URL")


@pytest.mark.skipif(
    not _PG_TEST_URL,
    reason="Postgres-only: set MAGTI_PG_TEST_URL to a DISPOSABLE postgres database",
)
def test_chain_health_tamper_detection_postgres():
    """Full trigger + batch-health proof on real Postgres: chained inserts
    verify ok; a field edit, a row deletion, and a deletion just below the
    window boundary are each detected. DESTRUCTIVE — truncates audit_logs in
    the target DB, so MAGTI_PG_TEST_URL must point at a throwaway database.

    Calls main.audit_chain_health directly with a Postgres session (conftest
    hardwires the HTTP app to the SQLite test engine); the HTTP/permission
    layer is covered by the SQLite test above.
    """
    from uuid import uuid4

    from sqlalchemy import create_engine, text as sql_text
    from sqlalchemy.orm import sessionmaker

    import main as main_module
    import migrate

    pg_engine = create_engine(_PG_TEST_URL)
    models.Base.metadata.create_all(bind=pg_engine)
    with pg_engine.begin() as conn:
        # Applied twice on purpose: the second pass proves the CREATE OR
        # REPLACE / DROP-IF-EXISTS statements are a clean idempotent no-op.
        for _ in range(2):
            for stmt in migrate.AUDIT_CHAIN_STATEMENTS:
                conn.execute(sql_text(stmt))

    db = sessionmaker(bind=pg_engine)()
    try:
        actor = db.query(models.User).first()
        if actor is None:
            actor = models.User(
                email=f"pgchain_{uuid4().hex[:8]}@magti.ge", name="pg chain actor",
                role="admin", department="All", is_active=True, hashed_password="x",
            )
            db.add(actor)
            db.commit()
            db.refresh(actor)

        def reset_and_insert(count):
            db.execute(sql_text("TRUNCATE audit_logs RESTART IDENTITY"))
            db.commit()
            for i in range(count):
                db.add(models.AuditLog(
                    admin_id=actor.id, action="CREATE", item_type="category", item_id=1000 + i,
                ))
                db.commit()  # one txn per row => deterministic chain order
            return [r[0] for r in db.execute(sql_text("SELECT id FROM audit_logs ORDER BY id"))]

        def health(n=100):
            return main_module.audit_chain_health(n=n, current_user=actor, db=db)

        # Phase A — legit chain, then a field edit (stored hash now stale).
        ids = reset_and_insert(5)
        h = health()
        assert (h["status"], h["checked"], h["hash_mismatches"], h["link_breaks"]) == ("ok", 5, 0, 0)
        assert h["unchained_total"] == 0

        db.execute(sql_text("UPDATE audit_logs SET action='TAMPERED' WHERE id=:i"), {"i": ids[2]})
        db.commit()
        h = health()
        assert h["status"] == "tampered"
        assert h["hash_mismatches"] == 1 and h["link_breaks"] == 0
        assert ids[2] in h["bad_ids"]

        db.execute(sql_text("UPDATE audit_logs SET action='CREATE' WHERE id=:i"), {"i": ids[2]})
        db.commit()
        assert health()["status"] == "ok"

        # Phase B — deleted row: the successor's prev_hash no longer matches
        # its (new) predecessor's row_hash.
        db.execute(sql_text("DELETE FROM audit_logs WHERE id=:i"), {"i": ids[2]})
        db.commit()
        h = health()
        assert h["status"] == "tampered"
        assert h["hash_mismatches"] == 0 and h["link_breaks"] >= 1
        assert ids[3] in h["bad_ids"]

        # Phase C — deletion just BELOW the window: only the boundary-
        # predecessor query (query 2) can catch this; a window-only walk
        # would skip the oldest row's link check entirely.
        ids = reset_and_insert(6)
        db.execute(sql_text("DELETE FROM audit_logs WHERE id=:i"), {"i": ids[2]})  # id 3, window is 4..6
        db.commit()
        h = health(n=3)
        assert (h["window"], h["checked"]) == (3, 3)
        assert h["status"] == "tampered" and h["link_breaks"] >= 1
        assert ids[3] in h["bad_ids"]
    finally:
        db.close()
        pg_engine.dispose()


def test_export_audit_logs_filters_by_user_id(db_session):
    """CSV export must honor ?user_id=... exactly like the list endpoint does.

    Regression test: export_audit_logs previously had no user_id parameter at
    all (only user_name), even though _build_audit_query already supported
    it and the audit-dashboard grid's actor:<id> search already sent it —
    FastAPI silently drops unknown query params, so a filtered export
    silently included every actor's rows instead of just the one filtered on.
    """
    import uuid

    monolith_app.dependency_overrides.clear()

    unique = uuid.uuid4().hex[:8]
    target = make_user(db_session, email=f"export_target_{unique}@magti.ge", role="operator")
    other = make_user(db_session, email=f"export_other_{unique}@magti.ge", role="operator")
    db_session.add(models.AuditLog(admin_id=target.id, action="LOGIN", item_type="user", item_id=target.id))
    db_session.add(models.AuditLog(admin_id=other.id, action="LOGIN", item_type="user", item_id=other.id))
    db_session.commit()

    try:
        with TestClient(monolith_app) as tc:
            login = tc.post("/api/auth/login", json={"email": "admin@magti.ge", "password": "x"})
            assert login.status_code == 200, login.text
            headers = {"Authorization": "Bearer " + login.json()["access_token"]}

            res = tc.get(f"/api/audit-logs/export?user_id={target.id}", headers=headers)
            assert res.status_code == 200, res.text
            body = res.text
            assert target.name in body, "export must include the filtered actor's row"
            assert other.name not in body, "export must NOT include a different actor's row"
    finally:
        db_session.query(models.AuditLog).filter(
            models.AuditLog.admin_id.in_([target.id, other.id])
        ).delete(synchronize_session=False)
        db_session.delete(target)
        db_session.delete(other)
        db_session.commit()


def test_ensure_current_version_archived_is_idempotent(db_session):
    """Calling _ensure_current_version_archived twice for the same article
    version must not create a duplicate article_history row.

    Regression test: get_article_versions' self-healing insert-on-GET used to
    reimplement this check inline with no unique constraint backing it, so
    two concurrent requests viewing the same under-migrated article could
    both insert a row for the same (article_id, version_id). This exercises
    the ordinary (non-racy) path the exists-check already covers; the new
    ux_article_history_article_version unique index (migrate.py) plus the
    IntegrityError retry in _ensure_current_version_archived (main.py) are
    what additionally close the genuine cross-request race, which needs real
    concurrent connections to reproduce and is covered by manual/integration
    testing rather than a single-session unit test.
    """
    import uuid

    from routers.articles import _ensure_current_version_archived
    from tests.factories import make_article

    actor = make_user(db_session, email=f"archive_idem_actor_{uuid.uuid4().hex[:8]}@magti.ge", role="admin")
    article = make_article(db_session, author=actor, title="Archive Idempotent Article")

    try:
        _ensure_current_version_archived(db_session, article, actor.id)
        _ensure_current_version_archived(db_session, article, actor.id)
        db_session.commit()

        rows = db_session.query(models.ArticleHistory).filter(
            models.ArticleHistory.article_id == article.id,
            models.ArticleHistory.version_id == article.version,
        ).all()
        assert len(rows) == 1, "second call must not create a duplicate history row"
    finally:
        db_session.rollback()
        db_session.query(models.ArticleHistory).filter(
            models.ArticleHistory.article_id == article.id
        ).delete(synchronize_session=False)
        db_session.delete(article)
        db_session.delete(actor)
        db_session.commit()


def test_article_history_unique_index_rejects_duplicate_version(db_session):
    """Direct proof that ux_article_history_article_version (migrate.py) is
    actually in effect: a second, independently-committed row for the same
    (article_id, version_id) must be rejected at the database level.

    This is the mechanism _ensure_current_version_archived's IntegrityError
    retry depends on — if this index were ever missing or dropped, that
    retry logic would silently stop protecting against duplicates.
    """
    import uuid

    from sqlalchemy.exc import IntegrityError

    from tests.factories import make_article

    actor = make_user(db_session, email=f"archive_index_actor_{uuid.uuid4().hex[:8]}@magti.ge", role="admin")
    article = make_article(db_session, author=actor, title="Archive Index Article")

    try:
        first = models.ArticleHistory(
            article_id=article.id, title=article.title, content=article.content,
            updated_by=actor.id, version_id=1,
        )
        db_session.add(first)
        db_session.commit()

        second = models.ArticleHistory(
            article_id=article.id, title=article.title, content=article.content,
            updated_by=actor.id, version_id=1,
        )
        db_session.add(second)
        with pytest.raises(IntegrityError):
            db_session.flush()
    finally:
        db_session.rollback()
        db_session.query(models.ArticleHistory).filter(
            models.ArticleHistory.article_id == article.id
        ).delete(synchronize_session=False)
        db_session.delete(article)
        db_session.delete(actor)
        db_session.commit()

