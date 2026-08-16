-- BL-12: read receipts and view logs survived deletion but became
-- unlinkable.
--
-- Both tables use ON DELETE SET NULL for article_id (V19:15, V20:14),
-- unlike their siblings which cascade. That is deliberate -- a receipt is
-- compliance evidence and should outlive the article it is about -- and
-- both tables already snapshot everything a reader needs: the article
-- title and version, and the operator's name, email and department at the
-- moment of the read.
--
-- Everything except the identity. Deleting an article set article_id to
-- NULL, and every read path in the application filters by article_id
-- (ArticleController:1036, :1195-1196). So the rows were retained and
-- simultaneously unreachable: neither the clean removal a CASCADE gives,
-- nor usable evidence. The audit's suggested fix was to denormalise the
-- article title, which turns out to have been there since V19 -- the
-- missing piece was never the title, it was the link.
--
-- article_id_snapshot carries the id with no foreign key, so nothing nulls
-- it. article_id keeps its FK and its SET NULL, and therefore keeps
-- telling you whether the article still exists -- the two columns together
-- say "this receipt is about article 482" and "482 is gone", which is
-- exactly what a compliance question needs to be answerable.
--
-- Backfilled from article_id so rows written before today are addressable
-- too. Rows whose article was already deleted before this migration cannot
-- be recovered -- their id is gone -- and stay NULL; they keep their title
-- snapshot, which is what is left of them.
ALTER TABLE article_read_receipts ADD (article_id_snapshot NUMBER);
ALTER TABLE article_view_logs ADD (article_id_snapshot NUMBER);

UPDATE article_read_receipts SET article_id_snapshot = article_id WHERE article_id IS NOT NULL;
UPDATE article_view_logs SET article_id_snapshot = article_id WHERE article_id IS NOT NULL;

-- The application's read paths query on this column now, so it carries the
-- same indexes the FK column had for those queries.
CREATE INDEX ix_read_receipts_snapshot_version ON article_read_receipts (article_id_snapshot, article_version);
CREATE INDEX ix_view_logs_snapshot_viewed ON article_view_logs (article_id_snapshot, viewed_at);
CREATE INDEX ix_view_logs_snapshot_version ON article_view_logs (article_id_snapshot, article_version);
