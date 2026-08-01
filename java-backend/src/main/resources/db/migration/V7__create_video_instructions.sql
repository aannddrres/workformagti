-- Mirrors models.py's VideoInstruction (models.py:240-253). category is a
-- free-text label here, not an FK to categories (see domain/VideoInstruction
-- javadoc).
CREATE TABLE video_instructions (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title             VARCHAR2(500 CHAR) NOT NULL,
    video_url         VARCHAR2(1000 CHAR) NOT NULL,
    category          VARCHAR2(200 CHAR),
    target_department VARCHAR2(200 CHAR) DEFAULT 'All',
    created_at        TIMESTAMP(6),
    views_count       NUMBER DEFAULT 0,
    tags              VARCHAR2(500 CHAR),
    is_archived       NUMBER(1) DEFAULT 0
);

CREATE INDEX ix_video_instructions_title ON video_instructions (title);
CREATE INDEX ix_video_instructions_department ON video_instructions (target_department);
CREATE INDEX ix_video_instructions_archived ON video_instructions (is_archived);
