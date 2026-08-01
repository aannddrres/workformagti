-- Mirrors models.py's Favorite (models.py:296-308).
CREATE TABLE favorites (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id   NUMBER NOT NULL,
    item_type VARCHAR2(20 CHAR) NOT NULL,
    item_id   NUMBER NOT NULL,
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_favorite_user_item UNIQUE (user_id, item_type, item_id)
);
