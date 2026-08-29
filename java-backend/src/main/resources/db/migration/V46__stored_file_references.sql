-- Which content references which uploaded file.
--
-- DEC-P01 resolved: an uploaded file must follow the audience of the content
-- that carries it, rather than being readable by any authenticated employee
-- who knows its URL. UAT finding F-1 demonstrated the gap concretely -- an
-- ოფისი operator downloaded a 51 KB PNG belonging to a ტექნიკური article
-- that answers 404 for them.
--
-- The answer to "may this user open this file" is "is there content
-- referencing it that this user may read". ContentLifecycleService already
-- asks a version of that question for trash and purge, but it asks it with
-- DBMS_LOB.INSTR across articles, news and video_instructions -- 70 ms per
-- file against a 123-article corpus, growing linearly. That is fine for a
-- purge run and unusable on an authorization path that fires for every image
-- in every article view. Hence an index rather than a scan.
--
-- Deliberately a separate table rather than columns on stored_files: one file
-- can legitimately be referenced by several items (a shared diagram, an
-- article and the news post announcing it), and reference counting is
-- already how the purge logic reasons about them.

CREATE TABLE stored_file_references (
    filename   VARCHAR2(255 CHAR) NOT NULL,
    item_type  VARCHAR2(16 CHAR)  NOT NULL,
    item_id    NUMBER(19)         NOT NULL,
    CONSTRAINT pk_stored_file_references PRIMARY KEY (filename, item_type, item_id),
    CONSTRAINT ck_stored_file_ref_type CHECK (item_type IN ('article', 'news', 'video'))
);

-- The authorization path looks up by filename and then checks the referenced
-- items, so filename leads. The primary key already covers that; this second
-- index serves the opposite direction -- "forget everything about item X" on
-- delete and re-sync, which runs on every content save.
CREATE INDEX ix_stored_file_refs_item ON stored_file_references (item_type, item_id);

-- Backfill from the content that exists today, using the same reference shape
-- the application matches at runtime: the stored filename appearing anywhere
-- in the body or in the attachment/video URL. Substring matching rather than
-- URL parsing on purpose -- it is what ContentLifecycleService.referenceCount
-- already does, and the two must not disagree about what "referenced" means.
INSERT INTO stored_file_references (filename, item_type, item_id)
SELECT f.filename, 'article', a.id
FROM stored_files f
JOIN articles a
  ON DBMS_LOB.INSTR(a.content, f.filename) > 0
  OR INSTR(NVL(a.attachment_url, ' '), f.filename) > 0;

INSERT INTO stored_file_references (filename, item_type, item_id)
SELECT f.filename, 'news', n.id
FROM stored_files f
JOIN news n
  ON DBMS_LOB.INSTR(n.content, f.filename) > 0
  OR INSTR(NVL(n.attachment_url, ' '), f.filename) > 0;

INSERT INTO stored_file_references (filename, item_type, item_id)
SELECT f.filename, 'video', v.id
FROM stored_files f
JOIN video_instructions v
  ON INSTR(NVL(v.video_url, ' '), f.filename) > 0;

COMMIT;
