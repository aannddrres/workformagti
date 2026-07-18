"""Regression: quiz-attempt grading/attempt_number bookkeeping and the
knowledge-score first-try-bonus formula had no dedicated coverage
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
    db.query(models.QuizAttempt).filter(models.QuizAttempt.article_id == article_id).delete()
    db.query(models.QuizQuestion).filter(models.QuizQuestion.article_id == article_id).delete()
    db.query(models.ArticleHistory).filter(models.ArticleHistory.article_id == article_id).delete()
    db.query(models.ArticleTargetDepartment).filter(
        models.ArticleTargetDepartment.article_id == article_id
    ).delete()
    db.query(models.Article).filter(models.Article.id == article_id).delete()


def test_quiz_attempt_grading_and_attempt_number_increments(db_session):
    admin = make_user(db_session, email="factory_quiz_admin1@magti.ge", role="admin")
    operator = make_user(db_session, email="factory_quiz_op1@magti.ge", role="operator")
    article = make_article(db_session, author=admin, title="Quiz Attempt Marker", quiz_enabled=True)

    monolith_app.dependency_overrides.clear()
    try:
        with TestClient(monolith_app) as tc:
            # Set up one question with a known-correct answer, as admin.
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
            wrong_answer_id = next(a["id"] for a in question["answers"] if not a["is_correct"])

            # Operator submits a wrong answer first — attempt_number 1, not passed.
            monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
            res = tc.post(f"/api/articles/{article.id}/quiz/attempt", json={
                "answers": {str(question["id"]): wrong_answer_id},
            })
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["passed"] is False
            assert body["attempt_number"] == 1

            # Retry with the correct answer — attempt_number must increment to 2.
            res = tc.post(f"/api/articles/{article.id}/quiz/attempt", json={
                "answers": {str(question["id"]): correct_answer_id},
            })
            assert res.status_code == 200, res.text
            body2 = res.json()
            assert body2["passed"] is True
            assert body2["attempt_number"] == 2
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup_article(db_session, article.id)
        db_session.delete(operator)
        db_session.delete(admin)
        db_session.commit()


def test_knowledge_score_first_try_bonus(db_session):
    """+10 per distinct article passed, +5 extra if passed on attempt_number 1."""
    admin = make_user(db_session, email="factory_quiz_admin2@magti.ge", role="admin")
    operator = make_user(db_session, email="factory_quiz_op2@magti.ge", role="operator")
    article = make_article(db_session, author=admin, title="Knowledge Score Marker", quiz_enabled=True)

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

            # Pass on the FIRST attempt.
            monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
            res = tc.post(f"/api/articles/{article.id}/quiz/attempt", json={
                "answers": {str(question["id"]): correct_answer_id},
            })
            assert res.status_code == 200, res.text
            assert res.json()["attempt_number"] == 1
            assert res.json()["passed"] is True

            res = tc.get("/api/users/me/knowledge-score")
            assert res.status_code == 200, res.text
            score_body = res.json()
            assert score_body["articles_passed"] == 1
            assert score_body["first_try_passes"] == 1
            assert score_body["score"] == 15  # 10 (passed) + 5 (first-try bonus)
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup_article(db_session, article.id)
        db_session.delete(operator)
        db_session.delete(admin)
        db_session.commit()
