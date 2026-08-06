-- Hand-built substring-search index. Oracle has no pg_trgm equivalent (the
-- live Postgres app's search relies on pg_trgm GIN indexes, CLAUDE.md) and
-- Oracle Text's own wildcard substring matching was verified unreliable for
-- Georgian text during this domain's design phase (see migration doc's
-- Search section). One row per distinct trigram found anywhere in an
-- entity's searchable text (title+content+tags for articles, title+content
-- for news, title+category for videos).
--
-- Used purely as a fast candidate pre-filter: SearchQueryService always
-- re-verifies a candidate with an exact substring check against the real
-- column before counting a match, so no correctness depends on this table
-- alone -- only speed. No foreign key on entity_id: entity_type picks which
-- of 3 different tables it refers to, the same polymorphic-reference shape
-- audit_logs.item_type/item_id already uses.
CREATE TABLE search_trigrams (
    id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type   VARCHAR2(10 CHAR) NOT NULL,
    entity_id     NUMBER NOT NULL,
    trigram       VARCHAR2(3 CHAR) NOT NULL,
    CONSTRAINT uq_search_trigrams UNIQUE (entity_type, entity_id, trigram)
);

-- Lookup path: "which entities of this type contain trigram X" (search-time).
CREATE INDEX ix_search_trigrams_lookup ON search_trigrams (entity_type, trigram, entity_id);
