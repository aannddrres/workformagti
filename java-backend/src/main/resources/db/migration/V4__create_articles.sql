-- Mirrors models.py's Article (models.py:111-186). target_departments
-- (the junction-table-backed list) is NOT a column here -- see
-- V6__create_article_target_departments.sql and domain/Article.java's
-- @Transient field.
CREATE TABLE articles (
    id                       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title                    VARCHAR2(500 CHAR) NOT NULL,
    content                  CLOB NOT NULL,
    category_id              NUMBER,
    tags                     VARCHAR2(500 CHAR),
    target_department        VARCHAR2(200 CHAR) DEFAULT 'All',
    audience_profile         VARCHAR2(20 CHAR) DEFAULT 'all',
    created_at               TIMESTAMP(6),
    updated_at               TIMESTAMP(6),
    version                  NUMBER DEFAULT 1,
    author_id                NUMBER,
    status                   VARCHAR2(30 CHAR) DEFAULT 'draft',
    youtube_id               VARCHAR2(50 CHAR),
    published_at             TIMESTAMP(6),
    attachment_url           VARCHAR2(1000 CHAR),
    last_verified_at         TIMESTAMP(6),
    visible_to_tech_info     NUMBER(1) DEFAULT 1,
    visible_to_service_center NUMBER(1) DEFAULT 0,
    is_draft                 NUMBER(1) DEFAULT 1,
    quiz_enabled             NUMBER(1) DEFAULT 0,
    CONSTRAINT fk_articles_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_articles_author FOREIGN KEY (author_id) REFERENCES users (id)
);

CREATE INDEX ix_articles_title ON articles (title);
CREATE INDEX ix_articles_category_id ON articles (category_id);
CREATE INDEX ix_articles_audience_profile ON articles (audience_profile);
CREATE INDEX ix_articles_author_id ON articles (author_id);
-- Matches models.py's ix_articles_department_status (models.py:117).
CREATE INDEX ix_articles_department_status ON articles (target_department, status);
