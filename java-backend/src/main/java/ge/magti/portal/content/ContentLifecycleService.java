package ge.magti.portal.content;

import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R5 content lifecycle: archive is owned by the content controllers; this
 * service owns the separate recoverable trash and evidence-safe purge.
 *
 * <p>No scheduled purge exists. A SYSTEM_ADMIN must explicitly request each
 * due purge, and the operation fails closed before 30 days or under legal
 * hold. That keeps the local Oracle implementation useful without pretending
 * the still-open production storage/retention contract has been approved.
 */
@Service
public class ContentLifecycleService {

    public static final int RECOVERY_DAYS = 30;
    private static final Pattern UPLOAD_REFERENCE = Pattern.compile(
            "(?:^|[/\\\\])uploads[/\\\\]([A-Za-z0-9._-]{1,100})(?:[?#][^\\\"'\\s<]*)?",
            Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;
    private final ContentDeletionService contentDeletionService;
    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;

    public ContentLifecycleService(
            JdbcTemplate jdbcTemplate,
            ContentDeletionService contentDeletionService,
            AuditLogRepository auditLogRepository,
            EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.contentDeletionService = contentDeletionService;
        this.auditLogRepository = auditLogRepository;
        this.entityManager = entityManager;
    }

    public enum Status {
        OK,
        NOT_FOUND,
        NOT_ARCHIVED,
        RECOVERY_EXPIRED,
        PURGE_NOT_DUE,
        LEGAL_HOLD
    }

    public enum ItemType {
        ARTICLE("article", "articles"),
        NEWS("news", "news"),
        VIDEO("video", "video_instructions");

        private final String wireName;
        private final String tableName;

        ItemType(String wireName, String tableName) {
            this.wireName = wireName;
            this.tableName = tableName;
        }

        public String wireName() {
            return wireName;
        }

        String tableName() {
            return tableName;
        }

        public static ItemType fromWireName(String value) {
            for (ItemType type : values()) {
                if (type.wireName.equals(value == null ? "" : value.toLowerCase(Locale.ROOT))) {
                    return type;
                }
            }
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<ContentTrashItem> listTrash() {
        String sql = """
                SELECT t.item_type, t.item_id, t.title, t.trashed_at, t.purge_after,
                       t.trashed_by, u.name, t.legal_hold
                FROM (
                    SELECT 'article' item_type, id item_id, title, trashed_at, purge_after, trashed_by, legal_hold
                    FROM articles WHERE trashed_at IS NOT NULL
                    UNION ALL
                    SELECT 'news', id, title, trashed_at, purge_after, trashed_by, legal_hold
                    FROM news WHERE trashed_at IS NOT NULL
                    UNION ALL
                    SELECT 'video', id, title, trashed_at, purge_after, trashed_by, legal_hold
                    FROM video_instructions WHERE trashed_at IS NOT NULL
                ) t
                LEFT JOIN users u ON u.id = t.trashed_by
                ORDER BY t.trashed_at DESC, t.item_type, t.item_id
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ContentTrashItem(
                rs.getString(1), rs.getLong(2), rs.getString(3),
                atTbilisi(rs.getTimestamp(4)), atTbilisi(rs.getTimestamp(5)),
                rs.getLong(6), rs.getString(7), rs.getInt(8) == 1));
    }

    @Transactional
    public Status moveToTrash(ItemType type, Long itemId, User actor) {
        // Archive endpoints update through JPA and then enter this JDBC-backed
        // lifecycle in the same transaction. Flush first so the archive gate
        // evaluates the database's current state, not its pre-request value.
        entityManager.flush();
        Payload payload = loadPayload(type, itemId, false);
        if (payload == null) {
            return Status.NOT_FOUND;
        }
        if (!payload.archived()) {
            return Status.NOT_ARCHIVED;
        }

        OffsetDateTime trashedAt = TbilisiTime.now();
        OffsetDateTime purgeAfter = trashedAt.plusDays(RECOVERY_DAYS);
        int updated = jdbcTemplate.update("UPDATE " + type.tableName()
                        + " SET trashed_at = ?, purge_after = ?, trashed_by = ?, legal_hold = 0"
                        + " WHERE id = ? AND trashed_at IS NULL",
                Timestamp.valueOf(trashedAt.toLocalDateTime()),
                Timestamp.valueOf(purgeAfter.toLocalDateTime()), actor.getId(), itemId);
        if (updated != 1) {
            return Status.NOT_FOUND;
        }

        trashUnreferencedFiles(payload.filenames(), actor.getId(), trashedAt, purgeAfter);
        writeAudit(actor, "TRASH", type, itemId, payload.title());
        return Status.OK;
    }

    @Transactional
    public Status restore(ItemType type, Long itemId, User actor) {
        Payload payload = loadPayload(type, itemId, true);
        if (payload == null) {
            return Status.NOT_FOUND;
        }
        OffsetDateTime now = TbilisiTime.now();
        if (!payload.purgeAfter().isAfter(now)) {
            return Status.RECOVERY_EXPIRED;
        }

        int updated = jdbcTemplate.update("UPDATE " + type.tableName()
                        + " SET trashed_at = NULL, purge_after = NULL, trashed_by = NULL, legal_hold = 0"
                        + " WHERE id = ? AND trashed_at IS NOT NULL",
                itemId);
        if (updated != 1) {
            return Status.NOT_FOUND;
        }
        restoreFiles(payload.filenames());
        writeAudit(actor, "RESTORE_FROM_TRASH", type, itemId, payload.title());
        return Status.OK;
    }

    @Transactional
    public Status purge(ItemType type, Long itemId, User actor) {
        Payload payload = loadPayload(type, itemId, true);
        if (payload == null) {
            return Status.NOT_FOUND;
        }
        if (payload.legalHold()) {
            return Status.LEGAL_HOLD;
        }
        if (payload.purgeAfter().isAfter(TbilisiTime.now())) {
            return Status.PURGE_NOT_DUE;
        }

        snapshotEvidence(type, itemId, payload.title());
        contentDeletionService.purgeNonEvidenceReferences(type.wireName(), itemId);
        int deleted = jdbcTemplate.update("DELETE FROM " + type.tableName()
                + " WHERE id = ? AND trashed_at IS NOT NULL AND legal_hold = 0 AND purge_after <= ?",
                itemId, Timestamp.valueOf(TbilisiTime.now().toLocalDateTime()));
        if (deleted != 1) {
            return Status.PURGE_NOT_DUE;
        }
        purgeOrphanFiles(payload.filenames());
        writeAudit(actor, "PURGE", type, itemId, payload.title());
        return Status.OK;
    }

    private Payload loadPayload(ItemType type, Long itemId, boolean trashed) {
        String select = switch (type) {
            case ARTICLE -> "SELECT title, content, attachment_url, "
                    + "CASE WHEN status = 'archived' THEN 1 ELSE 0 END, purge_after, legal_hold "
                    + "FROM articles WHERE id = ? AND trashed_at IS " + (trashed ? "NOT NULL" : "NULL");
            case NEWS -> "SELECT title, content, attachment_url, "
                    + "CASE WHEN expires_at IS NOT NULL AND expires_at <= ? THEN 1 ELSE 0 END, "
                    + "purge_after, legal_hold FROM news WHERE id = ? AND trashed_at IS "
                    + (trashed ? "NOT NULL" : "NULL");
            case VIDEO -> "SELECT title, CAST(NULL AS VARCHAR2(1)), video_url, is_archived, purge_after, legal_hold "
                    + "FROM video_instructions WHERE id = ? AND trashed_at IS " + (trashed ? "NOT NULL" : "NULL");
        };
        List<Payload> rows;
        if (type == ItemType.NEWS) {
            rows = jdbcTemplate.query(select, ps -> {
                ps.setTimestamp(1, Timestamp.valueOf(TbilisiTime.now().toLocalDateTime()));
                ps.setLong(2, itemId);
            }, (rs, rowNum) -> payload(rs.getString(1), clobText(rs.getObject(2)), rs.getString(3),
                    rs.getInt(4) == 1, rs.getTimestamp(5), rs.getInt(6) == 1));
        } else {
            rows = jdbcTemplate.query(select, ps -> ps.setLong(1, itemId),
                    (rs, rowNum) -> payload(rs.getString(1), clobText(rs.getObject(2)), rs.getString(3),
                            rs.getInt(4) == 1, rs.getTimestamp(5), rs.getInt(6) == 1));
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Payload payload(
            String title, String content, String directUrl, boolean archived,
            Timestamp purgeAfter, boolean legalHold) {
        Set<String> filenames = new LinkedHashSet<>();
        collectFilenames(content, filenames);
        collectFilenames(directUrl, filenames);
        return new Payload(title, archived, filenames,
                purgeAfter == null ? null : atTbilisi(purgeAfter), legalHold);
    }

    private void snapshotEvidence(ItemType type, Long itemId, String title) {
        jdbcTemplate.update("UPDATE required_readings SET item_title_snapshot = ? "
                + "WHERE item_type = ? AND item_id = ? AND item_title_snapshot IS NULL", title, type.wireName(), itemId);
        if (type == ItemType.ARTICLE) {
            jdbcTemplate.update("UPDATE quiz_attempts SET article_id_snapshot = NVL(article_id_snapshot, article_id), "
                    + "article_title_snapshot = NVL(article_title_snapshot, ?) WHERE article_id = ?", title, itemId);
        }
    }

    private void trashUnreferencedFiles(
            Set<String> filenames, Long actorId, OffsetDateTime trashedAt, OffsetDateTime purgeAfter) {
        for (String filename : filenames) {
            if (referenceCount(filename, true) == 0) {
                jdbcTemplate.update("UPDATE stored_files SET trashed_at = ?, purge_after = ?, trashed_by = ? "
                                + "WHERE filename = ? AND trashed_at IS NULL",
                        Timestamp.valueOf(trashedAt.toLocalDateTime()),
                        Timestamp.valueOf(purgeAfter.toLocalDateTime()), actorId, filename);
            }
        }
    }

    private void restoreFiles(Set<String> filenames) {
        for (String filename : filenames) {
            jdbcTemplate.update("UPDATE stored_files SET trashed_at = NULL, purge_after = NULL, trashed_by = NULL "
                    + "WHERE filename = ?", filename);
        }
    }

    private void purgeOrphanFiles(Set<String> filenames) {
        Timestamp now = Timestamp.valueOf(TbilisiTime.now().toLocalDateTime());
        for (String filename : filenames) {
            if (referenceCount(filename, false) == 0) {
                jdbcTemplate.update("DELETE FROM stored_files WHERE filename = ? AND trashed_at IS NOT NULL "
                        + "AND purge_after <= ? AND legal_hold = 0", filename, now);
            }
        }
    }

    private int referenceCount(String filename, boolean activeOnly) {
        String active = activeOnly ? " AND trashed_at IS NULL" : "";
        String sql = "SELECT COUNT(*) FROM ("
                + "SELECT id FROM articles WHERE (INSTR(attachment_url, ?) > 0 OR DBMS_LOB.INSTR(content, ?) > 0)" + active
                + " UNION ALL SELECT id FROM news WHERE (INSTR(attachment_url, ?) > 0 OR DBMS_LOB.INSTR(content, ?) > 0)" + active
                + " UNION ALL SELECT id FROM video_instructions WHERE INSTR(video_url, ?) > 0" + active
                + ") refs";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                filename, filename, filename, filename, filename);
        return count == null ? 0 : count;
    }

    private static void collectFilenames(String text, Set<String> filenames) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = UPLOAD_REFERENCE.matcher(text);
        while (matcher.find()) {
            filenames.add(matcher.group(1));
        }
    }

    private static String clobText(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return String.valueOf(value);
    }

    private static OffsetDateTime atTbilisi(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().atOffset(TbilisiTime.OFFSET);
    }

    private void writeAudit(User actor, String action, ItemType type, Long itemId, String title) {
        AuditLog audit = new AuditLog();
        audit.setAdminId(actor.getId());
        audit.setAdminNameSnapshot(actor.getName());
        audit.setAdminEmailSnapshot(actor.getEmail());
        audit.setAction(action);
        audit.setItemType(type.wireName());
        audit.setItemId(itemId);
        audit.setItemNameSnapshot(title);
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);
    }

    private record Payload(
            String title,
            boolean archived,
            Set<String> filenames,
            OffsetDateTime purgeAfter,
            boolean legalHold) {
    }
}
