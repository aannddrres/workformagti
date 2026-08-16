-- One-off cleanup for audit finding BL-02/BL-10.
--
-- Before ContentDeletionService existed, deleting an article, news item or
-- video left behind rows in required_readings (+ their read_statuses),
-- tags_mapping and favorites -- all polymorphic (item_type, item_id)
-- references with no FK, so nothing enforced or cleaned them. Every
-- deletion made before this fix shipped may have left orphans. This script
-- finds and removes them. It is idempotent: running it twice is a no-op the
-- second time.
--
-- Run manually, once, against production after the ContentDeletionService
-- migration deploys. Not run automatically by anything -- deleting rows
-- outside of what this deploy's own code changes touch should be a decision
-- a human makes at a specific moment, not something that happens as a side
-- effect of an app restart.
--
-- Read before running: this only knows about item_type IN ('article',
-- 'news', 'video'), matching the only types anything in this codebase
-- currently writes. If that set grows, extend the IN-lists below first.

-- 1. Read statuses for a required reading that already points at nothing.
DELETE FROM read_statuses
WHERE required_reading_id IN (
    SELECT rr.id FROM required_readings rr
    WHERE (rr.item_type = 'article' AND NOT EXISTS (SELECT 1 FROM articles a WHERE a.id = rr.item_id))
       OR (rr.item_type = 'news'    AND NOT EXISTS (SELECT 1 FROM news n WHERE n.id = rr.item_id))
       OR (rr.item_type = 'video'   AND NOT EXISTS (SELECT 1 FROM video_instructions v WHERE v.id = rr.item_id))
);

-- 2. The orphaned required_readings rows themselves (must run after #1 --
--    read_statuses.required_reading_id FKs to this table).
DELETE FROM required_readings rr
WHERE (rr.item_type = 'article' AND NOT EXISTS (SELECT 1 FROM articles a WHERE a.id = rr.item_id))
   OR (rr.item_type = 'news'    AND NOT EXISTS (SELECT 1 FROM news n WHERE n.id = rr.item_id))
   OR (rr.item_type = 'video'   AND NOT EXISTS (SELECT 1 FROM video_instructions v WHERE v.id = rr.item_id));

-- 3. Orphaned tag mappings.
DELETE FROM tags_mapping tm
WHERE (tm.item_type = 'article' AND NOT EXISTS (SELECT 1 FROM articles a WHERE a.id = tm.item_id))
   OR (tm.item_type = 'news'    AND NOT EXISTS (SELECT 1 FROM news n WHERE n.id = tm.item_id))
   OR (tm.item_type = 'video'   AND NOT EXISTS (SELECT 1 FROM video_instructions v WHERE v.id = tm.item_id));

-- 4. Orphaned favorites.
DELETE FROM favorites f
WHERE (f.item_type = 'article' AND NOT EXISTS (SELECT 1 FROM articles a WHERE a.id = f.item_id))
   OR (f.item_type = 'news'    AND NOT EXISTS (SELECT 1 FROM news n WHERE n.id = f.item_id))
   OR (f.item_type = 'video'   AND NOT EXISTS (SELECT 1 FROM video_instructions v WHERE v.id = f.item_id));

COMMIT;
