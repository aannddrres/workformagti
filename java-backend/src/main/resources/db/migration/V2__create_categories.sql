-- Mirrors models.py's Category (models.py:96-108). name/slug are indexed
-- but NOT unique in the source model -- mirrored exactly, not tightened.
CREATE TABLE categories (
    id                 NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name               VARCHAR2(200 CHAR) NOT NULL,
    parent_id          NUMBER,
    slug               VARCHAR2(150 CHAR),
    icon               VARCHAR2(100 CHAR),
    pastel_color_class VARCHAR2(100 CHAR),
    is_active          NUMBER(1) DEFAULT 1,
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id)
);

CREATE INDEX ix_categories_name ON categories (name);
-- Oracle does not auto-index FK columns (unlike some other engines).
CREATE INDEX ix_categories_parent_id ON categories (parent_id);
CREATE INDEX ix_categories_slug ON categories (slug);
