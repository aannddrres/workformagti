package ge.magti.portal.export;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Explicit-column, read-only inventory queries for SYSTEM_ADMIN exports. */
@Service
public class AdminExportQueryService {

    private static final int QUERY_LIMIT = ExportSizeGuard.MAX_ROWS + 1;
    private final JdbcTemplate jdbcTemplate;

    public AdminExportQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminExportDataset load(AdminExportFamily family, LocalDate from, LocalDate through) {
        Query query = switch (family) {
            case AUDIT_LEDGER -> auditLedger(from, through, false);
            case READ_EVIDENCE -> readEvidence(from, through);
            case ARTICLE_VIEWS -> articleViews(from, through);
            case SEARCH_HISTORY -> searchHistory(from, through);
            case QUIZ_ATTEMPTS -> quizAttempts(from, through);
            case CHANGE_EVENTS -> auditLedger(from, through, true);
        };
        List<List<Object>> rows = run(query);
        ExportSizeGuard.checkSize(rows.size());
        return new AdminExportDataset(family, query.headers(), rows);
    }

    private Query auditLedger(LocalDate from, LocalDate through, boolean changesOnly) {
        List<String> headers = changesOnly
                ? List.of("ჩანაწერის ID", "თარიღი", "კატეგორია", "მოქმედება", "ადმინის ID",
                "ადმინისტრატორი", "ობიექტის ტიპი", "ობიექტის ID", "ობიექტი", "IP მისამართი",
                "მომხმარებლის აგენტი", "დეტალები")
                : List.of("ჩანაწერის ID", "თარიღი", "ადმინის ID", "ადმინისტრატორი", "ადმინის ელფოსტა",
                "კატეგორია", "მოქმედება", "ობიექტის ტიპი", "ობიექტის ID", "ობიექტი", "დეტალები",
                "IP მისამართი", "მომხმარებლის აგენტი", "წინა ჰეში", "ჩანაწერის ჰეში");
        String columns = changesOnly
                ? "id, timestamp, category, action, admin_id, admin_name_snapshot, item_type, item_id, "
                + "item_name_snapshot, ip_address, user_agent, details"
                : "id, timestamp, admin_id, admin_name_snapshot, admin_email_snapshot, category, action, "
                + "item_type, item_id, item_name_snapshot, details, ip_address, user_agent, prev_hash, row_hash";
        FilteredSql filtered = filtered(
                "SELECT " + columns + " FROM audit_logs WHERE 1=1",
                "timestamp", from, through);
        if (changesOnly) {
            filtered = filtered.append(" AND category IN ('USER', 'CONTENT', 'SECURITY', 'SYSTEM')");
        }
        int detailsIndex = changesOnly ? 12 : 11;
        return new Query(headers, filtered.sql() + " ORDER BY timestamp DESC, id DESC FETCH FIRST "
                + QUERY_LIMIT + " ROWS ONLY", filtered.args(), detailsIndex);
    }

    private Query readEvidence(LocalDate from, LocalDate through) {
        List<String> headers = List.of(
                "მტკიცებულების ტიპი", "ჩანაწერის ID", "თანამშრომლის ID", "თანამშრომელი",
                "ელფოსტა", "დეპარტამენტი", "მასალის ტიპი", "მასალის ID", "მასალის სათაური",
                "ვერსია", "სტატუსი", "წაკითხვის თარიღი", "ვადა");
        String base = "SELECT * FROM ("
                + "SELECT 'ARTICLE_RECEIPT' evidence_type, r.id evidence_id, r.operator_id user_id, "
                + "r.operator_name_snapshot user_name, r.operator_email_snapshot email, "
                + "r.operator_department_snapshot department, 'article' item_type, "
                + "r.article_id_snapshot item_id, r.article_title_snapshot item_title, "
                + "r.article_version item_version, 'read' status, r.read_at event_at, "
                + "CAST(NULL AS TIMESTAMP) due_at FROM article_read_receipts r "
                + "UNION ALL "
                + "SELECT 'REQUIRED_READING', s.id, s.user_id, u.name, u.email, "
                + "s.operator_department_snapshot, rr.item_type, rr.item_id, "
                + "CASE WHEN rr.item_type = 'article' THEN a.title WHEN rr.item_type = 'news' THEN n.title "
                + "ELSE 'მასალა #' || TO_CHAR(rr.item_id) END, CAST(NULL AS NUMBER), s.status, s.read_at, rr.due_date "
                + "FROM read_statuses s JOIN users u ON u.id = s.user_id "
                + "JOIN required_readings rr ON rr.id = s.required_reading_id "
                + "LEFT JOIN articles a ON rr.item_type = 'article' AND a.id = rr.item_id "
                + "LEFT JOIN news n ON rr.item_type = 'news' AND n.id = rr.item_id) evidence WHERE 1=1";
        FilteredSql filtered = filtered(base, "event_at", from, through);
        return new Query(headers, filtered.sql() + " ORDER BY event_at DESC NULLS LAST, evidence_id DESC "
                + "FETCH FIRST " + QUERY_LIMIT + " ROWS ONLY", filtered.args(), -1);
    }

    private Query articleViews(LocalDate from, LocalDate through) {
        List<String> headers = List.of("ჩანაწერის ID", "გახსნის თარიღი", "თანამშრომლის ID",
                "თანამშრომელი", "ელფოსტა", "დეპარტამენტი", "სტატიის ID", "სტატიის სათაური", "ვერსია");
        FilteredSql filtered = filtered("SELECT id, viewed_at, operator_id, operator_name_snapshot, "
                + "operator_email_snapshot, operator_department_snapshot, article_id_snapshot, "
                + "article_title_snapshot, article_version FROM article_view_logs WHERE 1=1",
                "viewed_at", from, through);
        return new Query(headers, filtered.sql() + " ORDER BY viewed_at DESC, id DESC FETCH FIRST "
                + QUERY_LIMIT + " ROWS ONLY", filtered.args(), -1);
    }

    private Query searchHistory(LocalDate from, LocalDate through) {
        List<String> headers = List.of("ჩანაწერის ID", "ძებნის თარიღი", "თანამშრომლის ID",
                "თანამშრომელი", "ელფოსტა", "საძიებო ტექსტი", "იპოვა შედეგი", "შედეგების რაოდენობა");
        FilteredSql filtered = filtered("SELECT s.id, s.timestamp, s.user_id, u.name, u.email, "
                + "s.search_term, CASE WHEN s.has_results = 1 THEN 'კი' ELSE 'არა' END, s.results_found "
                + "FROM search_logs s JOIN users u ON u.id = s.user_id WHERE 1=1",
                "s.timestamp", from, through);
        return new Query(headers, filtered.sql() + " ORDER BY s.timestamp DESC, s.id DESC FETCH FIRST "
                + QUERY_LIMIT + " ROWS ONLY", filtered.args(), -1);
    }

    private Query quizAttempts(LocalDate from, LocalDate through) {
        List<String> headers = List.of("მცდელობის ID", "თარიღი", "თანამშრომლის ID", "თანამშრომელი",
                "ელფოსტა", "სტატიის ID", "სტატიის სათაური", "სტატიის ვერსია", "მცდელობის ნომერი",
                "ქულა", "კითხვების რაოდენობა", "ჩააბარა");
        FilteredSql filtered = filtered("SELECT q.id, q.created_at, q.user_id, u.name, u.email, "
                + "q.article_id, a.title, q.article_version, q.attempt_number, q.score, q.total_questions, "
                + "CASE WHEN q.passed = 1 THEN 'კი' ELSE 'არა' END FROM quiz_attempts q "
                + "JOIN users u ON u.id = q.user_id JOIN articles a ON a.id = q.article_id WHERE 1=1",
                "q.created_at", from, through);
        return new Query(headers, filtered.sql() + " ORDER BY q.created_at DESC, q.id DESC FETCH FIRST "
                + QUERY_LIMIT + " ROWS ONLY", filtered.args(), -1);
    }

    private List<List<Object>> run(Query query) {
        return jdbcTemplate.query(query.sql(), ps -> {
            for (int i = 0; i < query.args().size(); i++) ps.setObject(i + 1, query.args().get(i));
        }, (ResultSet rs, int rowNum) -> {
            List<Object> row = new ArrayList<>(query.headers().size());
            for (int i = 1; i <= query.headers().size(); i++) {
                Object value = value(rs, i);
                if (i == query.redactedJsonColumn() && value != null) {
                    value = ExportSecretRedactor.redactJson(String.valueOf(value));
                }
                row.add(value);
            }
            return row;
        });
    }

    private static Object value(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return value;
    }

    private static FilteredSql filtered(String base, String timestampColumn, LocalDate from, LocalDate through) {
        StringBuilder sql = new StringBuilder(base);
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND ").append(timestampColumn).append(" >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (through != null) {
            sql.append(" AND ").append(timestampColumn).append(" < ?");
            args.add(Timestamp.valueOf(through.plusDays(1).atStartOfDay()));
        }
        return new FilteredSql(sql.toString(), args);
    }

    private record Query(List<String> headers, String sql, List<Object> args, int redactedJsonColumn) {}
    private record FilteredSql(String sql, List<Object> args) {
        FilteredSql append(String suffix) { return new FilteredSql(sql + suffix, args); }
    }
}
