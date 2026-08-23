"""
A synthetic legacy portal database, built from the migration plan itself.

Every table in `spec.PLAN` gets created from its own source column list, so
this fixture cannot drift out of step with the plan the way a hand-written
DDL block would. The rows it seeds are chosen for the things that break on
the crossing rather than for realism:

* Georgian text everywhere, including a title of exactly 500 characters --
  which fits VARCHAR2(500 CHAR) and would not fit VARCHAR2(500) counted in
  bytes. If the schema ever loses its CHAR semantics, this row is where it
  shows.
* an article body well past 32k, the size at which a CLOB stops fitting a
  plain string bind;
* a category and a user whose parent/manager has a *higher* id than they do;
* an empty string, which Oracle stores as NULL;
* an audit log whose hashes are exactly what the Postgres trigger would have
  written -- computed here with the shared canonical implementation -- so a
  run against Oracle can compare the two chains for real.
"""
from __future__ import annotations

import sqlite3

from scripts.etl import audit_chain, spec

NOW = "2026-08-23 09:15:00.000000"
LATER = "2026-08-23 11:30:45.500000"

GEORGIAN_TITLE_500 = "ტარიფის ცვლილება " * 29 + "დასასრული"  # trimmed to 500 below
LONG_BODY = "<p>" + ("ტექსტი მრავალბაიტიანი სიმბოლოებით. " * 1200) + "</p>"


def _audit_rows() -> list[dict]:
    rows = [
        {
            "id": 1, "admin_id": 2, "action": "CREATE", "item_type": "article", "item_id": 1,
            "timestamp": NOW, "category": "CONTENT",
            "details": '{"title": {"new": "ტარიფის ცვლილება"}}',
            "admin_name_snapshot": "გიორგი კაპანაძე", "admin_email_snapshot": "admin@magti.ge",
            "item_name_snapshot": "ტარიფის ცვლილება", "ip_address": "10.0.0.5",
            "user_agent": "Mozilla/5.0",
        },
        {
            "id": 2, "admin_id": 2, "action": "UPDATE", "item_type": "article", "item_id": 1,
            "timestamp": LATER, "category": "CONTENT", "details": None,
            "admin_name_snapshot": "გიორგი კაპანაძე", "admin_email_snapshot": "admin@magti.ge",
            "item_name_snapshot": "ტარიფის ცვლილება", "ip_address": None, "user_agent": None,
        },
        {
            "id": 3, "admin_id": 1, "action": "LOGIN", "item_type": "user", "item_id": 1,
            "timestamp": LATER, "category": "SECURITY", "details": None,
            "admin_name_snapshot": "ნინო ბერიძე", "admin_email_snapshot": "manager@magti.ge",
            "item_name_snapshot": None, "ip_address": "10.0.0.9", "user_agent": "curl/8",
        },
    ]
    # Exactly the chain migrate.py's trigger would have produced.
    for row, (_, prev, digest) in zip(rows, audit_chain.recompute(rows)):
        row["prev_hash"] = prev
        row["row_hash"] = digest
    return rows


def create_schema(conn: sqlite3.Connection) -> None:
    for table in spec.PLAN:
        columns = list(dict.fromkeys(table.source_columns))
        if table.target == "audit_logs":
            columns += ["prev_hash", "row_hash"]
        conn.execute(f"CREATE TABLE {table.source} ({', '.join(columns)})")


def _insert(conn: sqlite3.Connection, table: str, rows: list[dict]) -> None:
    for row in rows:
        cols = ", ".join(row)
        marks = ", ".join("?" for _ in row)
        conn.execute(f"INSERT INTO {table} ({cols}) VALUES ({marks})", tuple(row.values()))


def seed(path: str) -> dict:
    """Build the database and return the facts the assertions need."""
    title_500 = GEORGIAN_TITLE_500[:500]
    conn = sqlite3.connect(path)
    create_schema(conn)

    _insert(conn, "teams", [{"id": 1, "name": "ტექნიკური — ჯგუფი 03", "created_at": NOW}])
    _insert(conn, "categories", [
        # parent_id points forward, at a row that does not exist yet
        {"id": 1, "name": "ტარიფები", "parent_id": 2, "slug": "tariffs", "is_active": 1},
        {"id": 2, "name": "ზოგადი", "parent_id": None, "slug": "general", "is_active": 1},
    ])
    _insert(conn, "users", [
        {
            "id": 1, "email": "manager@magti.ge", "name": "ნინო ბერიძე",
            "department": "ტექნიკური", "position": "ჯგუფის უფროსი", "phone": "",
            "role": "manager", "is_active": 1, "last_active": NOW,
            "hashed_password": "$2b$12$abcdefghijklmnopqrstuv", "permissions": None,
            "team_id": 1, "manager_id": 2, "last_news_viewed_at": None,
            "card_style": "corporate",
        },
        {
            "id": 2, "email": "admin@magti.ge", "name": "გიორგი კაპანაძე",
            "department": "ოფისი", "position": None, "phone": None, "role": "admin",
            "is_active": 1, "last_active": None, "hashed_password": "$2b$12$zyxwvutsrqponmlkjihgfe",
            "permissions": None, "team_id": None, "manager_id": None,
            "last_news_viewed_at": None, "card_style": None,
        },
        {
            # A legacy hand-granted permission the role does not give: the row
            # V36_1 must turn into an explicit ALLOW override after the load.
            "id": 3, "email": "operator@magti.ge", "name": "მარიამ ცხოვრებაძე",
            "department": "ტექნიკური", "position": "ოპერატორი", "phone": "+995322000000",
            "role": "operator", "is_active": 1, "last_active": NOW,
            "hashed_password": "$2b$12$0123456789abcdefghijkl",
            "permissions": '["reports.export"]', "team_id": 1, "manager_id": 1,
            "last_news_viewed_at": NOW, "card_style": "corporate",
        },
    ])
    _insert(conn, "articles", [
        {
            "id": 1, "title": title_500, "content": LONG_BODY, "category_id": 1,
            "tags": "ტარიფი,ცვლილება", "target_department": "All", "audience_profile": "all",
            "created_at": NOW, "updated_at": LATER, "version": 3, "author_id": 2,
            "status": "published", "youtube_id": None, "published_at": NOW,
            "attachment_url": "/uploads/doc.pdf", "last_verified_at": NOW,
            "visible_to_tech_info": 1, "visible_to_service_center": 0, "is_draft": 0,
            "quiz_enabled": 1,
        },
    ])
    _insert(conn, "news", [{
        "id": 1, "title": "სიახლე", "content": '<img src="/uploads/inline.png">',
        "target_department": "All", "created_at": NOW, "attachment_url": None, "version": 1,
        "visible_to_tech_info": 1, "visible_to_service_center": 0, "expires_at": None,
        "is_draft": 0, "author_id": 2,
    }])
    _insert(conn, "video_instructions", [{
        "id": 1, "title": "ინსტრუქცია", "video_url": "https://video.example/1",
        "category": "ზოგადი", "target_department": "All", "created_at": NOW,
        "views_count": 12, "tags": None, "is_archived": 0,
    }])
    _insert(conn, "article_target_departments", [
        {"article_id": 1, "department": "ტექნიკური"},
        {"article_id": 1, "department": "საინფორმაციო"},
    ])
    _insert(conn, "article_history", [{
        "id": 1, "article_id": 1, "title": "ძველი სათაური", "content": "<p>ძველი</p>",
        "updated_at": NOW, "updated_by": 2, "version_id": 2,
    }])
    _insert(conn, "news_history", [{
        "id": 1, "news_id": 1, "title": "ძველი სიახლე", "content": "<p>ძველი</p>",
        "attachment_url": None, "updated_at": NOW, "updated_by": 2,
    }])
    _insert(conn, "required_readings", [{
        "id": 1, "item_type": "article", "item_id": 1, "target_department": "ტექნიკური",
        "due_date": LATER, "priority": "high",
    }])
    _insert(conn, "read_statuses", [{
        "id": 1, "user_id": 3, "required_reading_id": 1, "status": "read", "read_at": LATER,
        "operator_department_snapshot": "ტექნიკური",
    }])
    _insert(conn, "tags", [{"id": 1, "name": "ტარიფი", "created_at": NOW}])
    _insert(conn, "tags_mapping", [{"id": 1, "tag_id": 1, "item_type": "article", "item_id": 1}])
    _insert(conn, "favorites", [{"id": 1, "user_id": 3, "item_type": "article", "item_id": 1}])
    _insert(conn, "article_read_receipts", [{
        "id": 1, "article_id": 1, "article_title_snapshot": title_500, "article_version": 3,
        "operator_id": 3, "operator_name_snapshot": "მარიამ ცხოვრებაძე",
        "operator_email_snapshot": "operator@magti.ge",
        "operator_department_snapshot": "ტექნიკური", "read_at": LATER,
    }])
    _insert(conn, "article_view_logs", [
        {
            "id": 1, "article_id": 1, "article_title_snapshot": title_500, "article_version": 3,
            "operator_id": 3, "operator_name_snapshot": "მარიამ ცხოვრებაძე",
            "operator_email_snapshot": "operator@magti.ge",
            "operator_department_snapshot": "ტექნიკური", "viewed_at": NOW,
        },
        {
            "id": 2, "article_id": 1, "article_title_snapshot": title_500, "article_version": 3,
            "operator_id": 1, "operator_name_snapshot": "ნინო ბერიძე",
            "operator_email_snapshot": "manager@magti.ge",
            "operator_department_snapshot": "ტექნიკური", "viewed_at": LATER,
        },
    ])
    _insert(conn, "quiz_questions", [{
        "id": 1, "article_id": 1, "question_text": "რა შეიცვალა ტარიფში?", "position": 0,
    }])
    _insert(conn, "quiz_answers", [
        {"id": 1, "question_id": 1, "answer_text": "ფასი", "is_correct": 1, "position": 0},
        {"id": 2, "question_id": 1, "answer_text": "სახელი", "is_correct": 0, "position": 1},
    ])
    _insert(conn, "quiz_attempts", [{
        "id": 1, "article_id": 1, "article_version": 3, "user_id": 3, "attempt_number": 1,
        "score": 1, "total_questions": 1, "passed": 1, "created_at": LATER,
    }])
    _insert(conn, "search_logs", [{
        "id": 1, "user_id": 3, "search_term": "ტარიფი", "timestamp": NOW, "has_results": 1,
        "results_found": 4,
    }])
    _insert(conn, "user_notes", [{
        "id": 1, "user_id": 3, "article_id": 1, "content": "შენიშვნა", "created_at": NOW,
        "updated_at": LATER,
    }])
    _insert(conn, "audit_action_translations", [{"id": 1, "action": "CREATE", "label_ka": "შექმნა"}])
    _insert(conn, "webhook_configs", [{
        "id": 1, "url": "https://hooks.example/magti", "is_active": 1,
        "trigger_actions": "LOGIN_FAILED",
    }])
    audit = _audit_rows()
    _insert(conn, "audit_logs", audit)
    conn.commit()
    conn.close()

    return {
        "title_500": title_500,
        "long_body": LONG_BODY,
        "audit": audit,
        "expected_rows": {
            "teams": 1, "categories": 2, "users": 3, "articles": 1, "news": 1,
            "video_instructions": 1, "article_target_departments": 2, "article_history": 1,
            "news_history": 1, "required_readings": 1, "read_statuses": 1, "tags": 1,
            "tags_mapping": 1, "favorites": 1, "article_read_receipts": 1,
            "article_view_logs": 2, "quiz_questions": 1, "quiz_answers": 2,
            "quiz_attempts": 1, "search_logs": 1, "user_notes": 1,
            "audit_action_translations": 1, "webhook_configs": 1, "audit_logs": 3,
        },
    }
