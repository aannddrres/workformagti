"""Regression: _check_quiz_gate's end-to-end enforcement on the read-receipt
route, and _get_eligible_operators' management-role exclusion on the
read-receipts grid, had no dedicated coverage
(docs/CODE_AUDIT_2026-07-11.md §5 Test Coverage Gap Analysis).
"""
import os
import sys

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


def _cleanup_article(db, article_id):
    db.query(models.ArticleReadReceipt).filter(models.ArticleReadReceipt.article_id == article_id).delete()
    db.query(models.QuizAttempt).filter(models.QuizAttempt.article_id == article_id).delete()
    db.query(models.QuizQuestion).filter(models.QuizQuestion.article_id == article_id).delete()
    db.query(models.ArticleHistory).filter(models.ArticleHistory.article_id == article_id).delete()
    db.query(models.ArticleTargetDepartment).filter(
        models.ArticleTargetDepartment.article_id == article_id
    ).delete()
    db.query(models.Article).filter(models.Article.id == article_id).delete()


def test_check_quiz_gate_blocks_read_receipt_until_quiz_passed(db_session):
    admin = make_user(db_session, email="factory_rr_gate_admin@magti.ge", role="admin")
    operator = make_user(db_session, email="factory_rr_gate_op@magti.ge", role="operator")
    article = make_article(db_session, author=admin, title="Quiz Gate Marker", quiz_enabled=True)

    monolith_app.dependency_overrides.clear()
    try:
        with TestClient(monolith_app) as tc:
            monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
            monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
            res = tc.put(f"/api/articles/{article.id}/quiz/admin", json={
                "questions": [{
                    "question_text": "2+2?",
                    "position": 0,
                    "answers": [
                        {"answer_text": "4", "is_correct": True, "position": 0},
                        {"answer_text": "5", "is_correct": False, "position": 1},
                    ],
                }],
            })
            assert res.status_code == 200, res.text
            question = res.json()["questions"][0]
            correct_answer_id = next(a["id"] for a in question["answers"] if a["is_correct"])

            monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
            # Blocked before passing the quiz.
            res = tc.post(f"/api/articles/{article.id}/read-receipt")
            assert res.status_code == 403, res.text

            no_receipt = db_session.query(models.ArticleReadReceipt).filter(
                models.ArticleReadReceipt.article_id == article.id,
                models.ArticleReadReceipt.operator_id == operator.id,
            ).first()
            assert no_receipt is None

            # Pass the quiz, then the read-receipt must go through.
            res = tc.post(f"/api/articles/{article.id}/quiz/attempt", json={
                "answers": {str(question["id"]): correct_answer_id},
            })
            assert res.status_code == 200, res.text
            assert res.json()["passed"] is True

            res = tc.post(f"/api/articles/{article.id}/read-receipt")
            assert res.status_code == 200, res.text

            receipt = db_session.query(models.ArticleReadReceipt).filter(
                models.ArticleReadReceipt.article_id == article.id,
                models.ArticleReadReceipt.operator_id == operator.id,
            ).first()
            assert receipt is not None
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup_article(db_session, article.id)
        db_session.delete(operator)
        db_session.delete(admin)
        db_session.commit()


def test_read_receipts_grid_excludes_management_roles(db_session):
    admin = make_user(db_session, email="factory_rr_grid_admin@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_rr_grid_op@magti.ge", role="operator", name="Eligible Grid Operator",
    )
    manager = make_user(
        db_session, email="factory_rr_grid_mgr@magti.ge", role="manager", name="Excluded Grid Manager",
    )
    article = make_article(db_session, author=admin, title="Read Receipts Grid Marker", target_department="All")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get(f"/api/articles/{article.id}/read-receipts")
            assert res.status_code == 200, res.text
            names = [r["operator_name"] for r in res.json()["receipts"]]
            assert "Eligible Grid Operator" in names
            assert "Excluded Grid Manager" not in names
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup_article(db_session, article.id)
        db_session.delete(operator)
        db_session.delete(manager)
        db_session.delete(admin)
        db_session.commit()
