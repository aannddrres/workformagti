-- Bug fix (found during PM migration-gap audit, 2026-08-11): V24/V25 created
-- fk_user_notes_article / fk_knowledge_feedback_article with Oracle's
-- default NO ACTION, unlike every sibling article-child table
-- (article_target_departments V17, article_history V18, quiz_questions V21,
-- quiz_attempts V26 -- all ON DELETE CASCADE). A personal note or feedback
-- report left on an article made DELETE /api/articles/{id} 500
-- (ORA-02292) instead of cleanly removing the now-orphaned rows.
ALTER TABLE user_notes DROP CONSTRAINT fk_user_notes_article;
ALTER TABLE user_notes ADD CONSTRAINT fk_user_notes_article
    FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE;

ALTER TABLE knowledge_feedback DROP CONSTRAINT fk_knowledge_feedback_article;
ALTER TABLE knowledge_feedback ADD CONSTRAINT fk_knowledge_feedback_article
    FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE;
