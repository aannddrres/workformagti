-- Which rows came from the retired portal, and where they came from.
--
-- The 122 real knowledge-base articles -- roaming tariffs, GPON parameters,
-- the porting procedure -- live in the old FastAPI application's SQLite
-- database and nowhere else. They have to reach Oracle as ordinary articles,
-- because that is what they are: the call centre needs them on day one, and
-- some of them will later be assigned as mandatory reading.
--
-- Until now the only thing that could move them was the presentation seeder,
-- which is deliberately unable to do this job. It refuses any target that is
-- not a local throwaway, requires the database to be EMPTY, and brings 602
-- invented employees with it. A one-way import into a real database is a
-- different operation and needs a different guarantee.
--
-- WHY A TABLE RATHER THAN A MARKER
--
-- Two things are needed that an audit row cannot give:
--
--   1. Re-running the importer must update what it already wrote, not
--      duplicate it. Without a source-to-target mapping the second run
--      produces 122 more articles and no way to tell the copies apart --
--      titles are not unique and the old ids are not preserved.
--
--   2. An administrator has to be able to FIND this batch afterwards. The
--      whole point of importing them as drafts is that they are released
--      gradually, which means answering "show me everything that came from
--      the old portal and is not published yet" long after the import ran.
--
-- The alternative -- a marker tag on articles.tags -- was rejected because
-- tags are shown to readers. Provenance is not content.
--
-- source_type is kept alongside source_id because the same importer moves
-- categories and stored files, and their id spaces overlap with articles'.
CREATE TABLE legacy_content_imports (
    id           NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type  VARCHAR2(30 CHAR) NOT NULL,
    source_id    NUMBER NOT NULL,
    target_id    NUMBER NOT NULL,
    imported_at  TIMESTAMP(6) NOT NULL,
    -- Which run wrote this row. Lets a single mistaken import be identified
    -- and undone without touching an earlier good one.
    batch        VARCHAR2(64 CHAR) NOT NULL,
    CONSTRAINT uq_legacy_import_source UNIQUE (source_type, source_id)
);

-- The lookup the importer does for every row it considers: "have I already
-- written this one?" Covered by the unique constraint's index.

-- The lookup the admin UI does: "which articles came from the old portal?"
CREATE INDEX ix_legacy_import_target ON legacy_content_imports (source_type, target_id);

-- The lookup for undoing one run.
CREATE INDEX ix_legacy_import_batch ON legacy_content_imports (batch);
