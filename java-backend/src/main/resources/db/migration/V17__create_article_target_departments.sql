-- Mirrors models.py's ArticleTargetDepartment (models.py:189-198).
CREATE TABLE article_target_departments (
    article_id NUMBER NOT NULL,
    department VARCHAR2(200 CHAR) NOT NULL,
    CONSTRAINT pk_article_target_departments PRIMARY KEY (article_id, department),
    CONSTRAINT fk_article_target_departments_article
        FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE
);

CREATE INDEX ix_article_target_departments_dept ON article_target_departments (department);
