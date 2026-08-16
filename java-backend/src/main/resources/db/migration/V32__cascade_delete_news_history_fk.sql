-- BL-01: fk_news_history_news was never part of V30's cascade sweep (only
-- user_notes/knowledge_feedback), so it stayed NO ACTION while every other
-- article-child table has ON DELETE CASCADE. updateNews writes a history row
-- on every edit (NewsController.java:160), so DELETE /api/news/{id} on any
-- previously-edited item throws ORA-02292 -> HTTP 500. Same fix shape as V30.
ALTER TABLE news_history DROP CONSTRAINT fk_news_history_news;
ALTER TABLE news_history ADD CONSTRAINT fk_news_history_news
    FOREIGN KEY (news_id) REFERENCES news (id) ON DELETE CASCADE;

-- fk_news_history_user deliberately left as NO ACTION: there is no user
-- deletion endpoint anywhere in the backend (UserController only
-- activates/deactivates), so nothing can trigger this today. Its sibling
-- fk_article_history_user (V18) is the same NO ACTION with the same
-- reasoning -- changing one without the other would be inconsistency for a
-- code path that does not exist. Revisit both together if a user-deletion
-- endpoint is ever added.
