-- Mirrors models.py's TagMapping (models.py:280-293).
CREATE TABLE tags_mapping (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tag_id    NUMBER NOT NULL,
    item_type VARCHAR2(20 CHAR) NOT NULL,
    item_id   NUMBER NOT NULL,
    CONSTRAINT fk_tags_mapping_tag FOREIGN KEY (tag_id) REFERENCES tags (id),
    CONSTRAINT uq_tag_mapping_item UNIQUE (tag_id, item_type, item_id)
);

CREATE INDEX ix_tags_mapping_item ON tags_mapping (item_type, item_id);
