package ge.magti.portal.audit;

import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.web.AuditLogResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Mirrors _build_audit_query (routers/audit_logs.py:109-175) plus the
 * extra {@code q} free-text filter both get_audit_logs and
 * export_audit_logs layer on top of it (:204-211, :276-283). Native SQL
 * via JdbcTemplate, not JPQL: the item-name resolution needs four LEFT
 * JOINs to unrelated entities keyed by a runtime {@code lower(item_type)}
 * match, which native SQL expresses far more directly than JPQL's ad-hoc
 * join syntax, and this mirrors AuditChainService's existing precedent of
 * using JdbcTemplate for this exact table.
 *
 * <p>Every join is 1:1 on a primary key (users.id / articles.id / news.id
 * / video_instructions.id / categories.id), so none of them can fan out a
 * row -- no {@code DISTINCT} needed, matching Python's plain
 * {@code .outerjoin(...)} chain.
 *
 * <p>Timestamp handling: the {@code audit_logs.timestamp} column is a
 * plain Oracle {@code TIMESTAMP(6)} with no zone data -- {@link
 * ge.magti.portal.util.TbilisiTimestampConverter} is JPA-only machinery,
 * so this hand-written SQL reproduces its exact convention itself:
 * bind/read a naive {@link LocalDateTime} and attach/detach {@link
 * TbilisiTime#OFFSET} at the Java boundary, never relying on any
 * driver-level timezone conversion.
 */
@Service
public class AuditLogQueryService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogQueryService.class);

    private static final String DELETED_USER_LABEL = "წაშლილი მომხმარებელი";

    private static final String SELECT_COLUMNS = """
            SELECT al.id, al.admin_id, al.action, al.item_type, al.item_id, al.timestamp,
                   al.category, al.details, al.admin_name_snapshot, al.item_name_snapshot,
                   al.prev_hash, al.row_hash, al.ip_address, al.user_agent,
                   u.name AS joined_admin_name,
                   art.title AS article_title, n.title AS news_title,
                   v.title AS video_title, c.name AS category_name
            """;

    private static final String FROM_JOINS = """
            FROM audit_logs al
            LEFT JOIN users u ON al.admin_id = u.id
            LEFT JOIN articles art ON LOWER(al.item_type) = 'article' AND al.item_id = art.id
            LEFT JOIN news n ON LOWER(al.item_type) = 'news' AND al.item_id = n.id
            LEFT JOIN video_instructions v ON LOWER(al.item_type) = 'video' AND al.item_id = v.id
            LEFT JOIN categories c ON LOWER(al.item_type) = 'category' AND al.item_id = c.id
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuditLogQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Page(List<AuditLogResponse> rows, long totalCount) {
    }

    private record Where(String sql, List<Object> params) {
    }

    /**
     * Mirrors get_audit_logs (:178-246). {@code scopeDepartments} is the
     * manager-only department pin, resolved by the caller: {@code null}
     * means unrestricted, an empty list means no rows (SEC-13 -- an
     * unassigned manager used to fall through to unrestricted).
     */
    public Page list(AuditLogFilter filter, List<String> scopeDepartments, int limit, int offset) {
        return list(filter, scopeDepartments, limit, offset, false);
    }

    /**
     * @param oldestFirst flips the chronological order. Only the timestamp is
     *     sortable on purpose: this is a tamper-evident trail, its meaningful
     *     order is time, and "find these rows" is already answered by the actor,
     *     category and date filters. The direction is a boolean rather than a
     *     column name so nothing a caller sends can reach the ORDER BY clause.
     */
    public Page list(AuditLogFilter filter, List<String> scopeDepartments, int limit, int offset,
            boolean oldestFirst) {
        Where where = buildWhere(filter, scopeDepartments);

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + FROM_JOINS + where.sql(), Long.class, where.params().toArray());

        List<Object> pageParams = new ArrayList<>(where.params());
        pageParams.add(offset);
        pageParams.add(limit);
        List<AuditLogResponse> rows = jdbcTemplate.query(
                SELECT_COLUMNS + FROM_JOINS + where.sql()
                        + (oldestFirst
                                ? " ORDER BY al.timestamp ASC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
                                : " ORDER BY al.timestamp DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"),
                (rs, rowNum) -> mapRow(rs),
                pageParams.toArray());

        return new Page(rows, total);
    }

    /**
     * Mirrors export_audit_logs' generate_csv() (:294-315): a forward-only,
     * server-side-cursor pass with a small JDBC fetch size, so a large
     * unfiltered export never materializes the full result set in memory --
     * the same memory-safety intent as Python's generator + yield_per(1000),
     * expressed via the JDBC idiom instead. No scope_department: matches
     * export_audit_logs' access rule (system admin / content admin only,
     * manager excluded entirely -- see AuditLogController's
     * requireSystemAuditNonManager), so there is no per-department slice to
     * apply here.
     */
    public void streamForExport(AuditLogFilter filter, Consumer<AuditLogResponse> consumer) {
        Where where = buildWhere(filter, null);
        String sql = SELECT_COLUMNS + FROM_JOINS + where.sql() + " ORDER BY al.timestamp DESC";
        List<Object> params = where.params();

        PreparedStatementCreator psc = con -> {
            PreparedStatement ps = con.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(1000);
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps;
        };
        RowCallbackHandler handler = rs -> consumer.accept(mapRow(rs));
        jdbcTemplate.query(psc, handler);
    }

    private Where buildWhere(AuditLogFilter filter, List<String> scopeDepartments) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        OffsetDateTime start = parseAuditDate(filter.startDate(), false);
        if (start != null) {
            sql.append(" AND al.timestamp >= ?");
            params.add(toColumnValue(start));
        }
        OffsetDateTime end = parseAuditDate(filter.endDate(), true);
        if (end != null) {
            sql.append(" AND al.timestamp < ?");
            params.add(toColumnValue(end));
        }
        if (filter.userId() != null) {
            sql.append(" AND al.admin_id = ?");
            params.add(filter.userId());
        }
        if (filter.userName() != null && !filter.userName().isBlank()) {
            sql.append(" AND UPPER(u.name) LIKE UPPER(?)");
            params.add("%" + filter.userName() + "%");
        }
        if (scopeDepartments != null) {
            if (scopeDepartments.isEmpty()) {
                // A department-scoped caller who can see nobody sees nothing.
                // Spelled out rather than left to an empty IN list, which is
                // a syntax error in Oracle, not an empty result.
                sql.append(" AND 1 = 0");
            } else {
                sql.append(" AND u.department IN (");
                for (int i = 0; i < scopeDepartments.size(); i++) {
                    sql.append(i == 0 ? "?" : ", ?");
                    params.add(scopeDepartments.get(i));
                }
                sql.append(")");
            }
        }
        if (filter.action() != null && !filter.action().isBlank()) {
            if ("LOGIN".equals(filter.action())) {
                // Aggregates password-based and SSO logins under one filter value.
                sql.append(" AND al.action IN ('LOGIN', 'LOGIN_SSO')");
            } else {
                sql.append(" AND al.action = ?");
                params.add(filter.action());
            }
        }
        if (filter.category() != null && !filter.category().isBlank()) {
            sql.append(" AND al.category = ?");
            params.add(filter.category().toUpperCase(Locale.ROOT));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            sql.append(" AND (UPPER(al.action) LIKE UPPER(?) OR UPPER(al.item_type) LIKE UPPER(?)"
                    + " OR UPPER(al.item_name_snapshot) LIKE UPPER(?) OR UPPER(al.details) LIKE UPPER(?))");
            String like = "%" + filter.q() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return new Where(sql.toString(), params);
    }

    private static LocalDateTime toColumnValue(OffsetDateTime value) {
        return value.withOffsetSameInstant(TbilisiTime.OFFSET).toLocalDateTime();
    }

    private AuditLogResponse mapRow(ResultSet rs) throws SQLException {
        String resolvedItemName = rs.getString("item_name_snapshot");
        if (resolvedItemName == null) {
            resolvedItemName = rs.getString("article_title");
        }
        if (resolvedItemName == null) {
            resolvedItemName = rs.getString("news_title");
        }
        if (resolvedItemName == null) {
            resolvedItemName = rs.getString("video_title");
        }
        if (resolvedItemName == null) {
            resolvedItemName = rs.getString("category_name");
        }

        String adminNameSnapshot = rs.getString("admin_name_snapshot");
        String joinedAdminName = rs.getString("joined_admin_name");
        String resolvedAdminName = adminNameSnapshot != null ? adminNameSnapshot
                : (joinedAdminName != null ? joinedAdminName : DELETED_USER_LABEL);

        LocalDateTime rawTimestamp = rs.getObject("timestamp", LocalDateTime.class);
        OffsetDateTime timestamp = rawTimestamp == null ? null : rawTimestamp.atOffset(TbilisiTime.OFFSET);

        return new AuditLogResponse(
                rs.getLong("id"),
                rs.getLong("admin_id"),
                resolvedAdminName,
                rs.getString("action"),
                rs.getString("item_type"),
                rs.getLong("item_id"),
                resolvedItemName,
                timestamp,
                rs.getString("category"),
                rs.getString("details"),
                rs.getString("prev_hash"),
                rs.getString("row_hash"),
                rs.getString("ip_address"),
                rs.getString("user_agent"));
    }

    /**
     * Mirrors _parse_audit_date (routers/audit_logs.py:26-65). The Python
     * version normalises to naive Tbilisi-local because its DB column is
     * naive; this Java port keeps every parsed value as an explicit
     * OffsetDateTime at TbilisiTime.OFFSET throughout, converting to the
     * column's naive representation only at the very end via {@link
     * #toColumnValue}, which is the safer of the two designs -- comparisons
     * stay instant-correct regardless of which offset a caller's input used.
     */
    private static OffsetDateTime parseAuditDate(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.endsWith("Z")) {
            text = text.substring(0, text.length() - 1) + "+00:00";
        }
        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(text).withOffsetSameInstant(TbilisiTime.OFFSET);
        } catch (DateTimeParseException notOffset) {
            try {
                parsed = LocalDateTime.parse(text).atOffset(TbilisiTime.OFFSET);
            } catch (DateTimeParseException notLocalDateTime) {
                try {
                    parsed = LocalDate.parse(text.split("T")[0]).atStartOfDay().atOffset(TbilisiTime.OFFSET);
                } catch (DateTimeParseException notDate) {
                    logger.warn("Audit log date parsing error: invalid format '{}'", value);
                    return null;
                }
            }
        }
        if (endOfDay && parsed.getHour() == 0 && parsed.getMinute() == 0 && parsed.getSecond() == 0) {
            // End-of-day inclusive: roll forward and apply a < filter at the call site.
            parsed = parsed.plusDays(1);
        }
        return parsed;
    }
}
