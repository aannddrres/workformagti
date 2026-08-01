-- Mirrors models.py's Tag (models.py:269-277).
-- No separate CREATE INDEX on name: the UNIQUE constraint below already
-- creates one, and Oracle rejects a second index on an identical column
-- list (ORA-01408).
CREATE TABLE tags (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR2(100 CHAR) NOT NULL,
    created_at TIMESTAMP(6),
    CONSTRAINT uq_tags_name UNIQUE (name)
);
