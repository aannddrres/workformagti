-- Mirrors models.py's Team (models.py:256-266).
-- VARCHAR2(n CHAR): character semantics, not byte semantics -- this is a
-- bilingual Georgian/English portal (CLAUDE.md) and AL32UTF8 is multi-byte,
-- so a plain VARCHAR2(200) would silently cap Georgian team names at ~100
-- characters, not 200.
CREATE TABLE teams (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR2(200 CHAR) NOT NULL,
    created_at TIMESTAMP(6),
    CONSTRAINT uq_teams_name UNIQUE (name)
);
