-- R5: a recoverable content trash is distinct from archive. Normal reads are
-- filtered by the application while the payload remains in place for 30 days.
-- No scheduler is introduced here: production purge activation remains gated
-- on the IT/Security/DPO storage, retention and legal-hold contract.
ALTER TABLE articles ADD (
    trashed_at  TIMESTAMP(6),
    purge_after TIMESTAMP(6),
    trashed_by  NUMBER,
    legal_hold  NUMBER(1) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_articles_legal_hold CHECK (legal_hold IN (0, 1)),
    CONSTRAINT ck_articles_trash_window CHECK (
        (trashed_at IS NULL AND purge_after IS NULL AND trashed_by IS NULL)
        OR (trashed_at IS NOT NULL AND purge_after IS NOT NULL AND trashed_by IS NOT NULL)
    )
);

ALTER TABLE news ADD (
    trashed_at  TIMESTAMP(6),
    purge_after TIMESTAMP(6),
    trashed_by  NUMBER,
    legal_hold  NUMBER(1) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_news_legal_hold CHECK (legal_hold IN (0, 1)),
    CONSTRAINT ck_news_trash_window CHECK (
        (trashed_at IS NULL AND purge_after IS NULL AND trashed_by IS NULL)
        OR (trashed_at IS NOT NULL AND purge_after IS NOT NULL AND trashed_by IS NOT NULL)
    )
);

ALTER TABLE video_instructions ADD (
    trashed_at  TIMESTAMP(6),
    purge_after TIMESTAMP(6),
    trashed_by  NUMBER,
    legal_hold  NUMBER(1) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_videos_legal_hold CHECK (legal_hold IN (0, 1)),
    CONSTRAINT ck_videos_trash_window CHECK (
        (trashed_at IS NULL AND purge_after IS NULL AND trashed_by IS NULL)
        OR (trashed_at IS NOT NULL AND purge_after IS NOT NULL AND trashed_by IS NOT NULL)
    )
);

ALTER TABLE stored_files ADD (
    trashed_at  TIMESTAMP(6),
    purge_after TIMESTAMP(6),
    trashed_by  NUMBER,
    legal_hold  NUMBER(1) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_stored_files_legal_hold CHECK (legal_hold IN (0, 1)),
    CONSTRAINT ck_stored_files_trash_window CHECK (
        (trashed_at IS NULL AND purge_after IS NULL AND trashed_by IS NULL)
        OR (trashed_at IS NOT NULL AND purge_after IS NOT NULL AND trashed_by IS NOT NULL)
    )
);

CREATE INDEX ix_articles_trash_due ON articles (trashed_at, purge_after, legal_hold);
CREATE INDEX ix_news_trash_due ON news (trashed_at, purge_after, legal_hold);
CREATE INDEX ix_videos_trash_due ON video_instructions (trashed_at, purge_after, legal_hold);
CREATE INDEX ix_stored_files_trash_due ON stored_files (trashed_at, purge_after, legal_hold);

-- Required-reading assignments and their read_status children are compliance
-- evidence. Keep a title snapshot so they remain intelligible after payload
-- purge even though item_id is intentionally polymorphic and has no FK.
ALTER TABLE required_readings ADD (item_title_snapshot VARCHAR2(500 CHAR));

UPDATE required_readings r
SET item_title_snapshot = CASE
    WHEN r.item_type = 'article' THEN (SELECT a.title FROM articles a WHERE a.id = r.item_id)
    WHEN r.item_type = 'news' THEN (SELECT n.title FROM news n WHERE n.id = r.item_id)
    WHEN r.item_type = 'video' THEN (SELECT v.title FROM video_instructions v WHERE v.id = r.item_id)
    ELSE 'მასალა #' || TO_CHAR(r.item_id)
END
WHERE item_title_snapshot IS NULL;

-- Quiz attempts are also evidence. Replace the old CASCADE with SET NULL and
-- retain both the identity and title as immutable snapshots.
ALTER TABLE quiz_attempts ADD (
    article_id_snapshot    NUMBER,
    article_title_snapshot VARCHAR2(500 CHAR)
);

UPDATE quiz_attempts q
SET article_id_snapshot = q.article_id,
    article_title_snapshot = (SELECT a.title FROM articles a WHERE a.id = q.article_id)
WHERE q.article_id_snapshot IS NULL;

ALTER TABLE quiz_attempts DROP CONSTRAINT fk_quiz_attempts_article;
ALTER TABLE quiz_attempts MODIFY (article_id NULL);
ALTER TABLE quiz_attempts ADD CONSTRAINT fk_quiz_attempts_article
    FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE SET NULL;

CREATE INDEX ix_quiz_attempts_article_snapshot ON quiz_attempts (article_id_snapshot, article_version);
