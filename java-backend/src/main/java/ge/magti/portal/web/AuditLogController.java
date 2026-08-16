package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.audit.AuditChainService;
import ge.magti.portal.audit.AuditLogFilter;
import ge.magti.portal.audit.AuditLogQueryService;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.CsvExportBuilder;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.ManagerScope;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.TbilisiTime;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mirrors routers/audit_logs.py in full: get_audit_logs (:178-246),
 * export_audit_logs (:249-321), verify_audit_log (:324-376) and
 * audit_chain_health (:379-455). Named for the whole router file, not just
 * the hash-chain half it started as -- see git history for the
 * AuditChainController -> AuditLogController rename that came with this
 * class picking up the other two endpoints.
 *
 * <p>Two distinct access rules coexist here, both ported exactly:
 * {@link #list} uses plain {@code system.audit} (a manager holds it too,
 * but is hard-pinned to their own department -- see {@link #scopeDepartment});
 * {@link #export}, {@link #verify} and {@link #chainHealth} additionally
 * exclude the manager role outright (bulk egress / integrity tooling, not
 * the scoped read view).
 */
@RestController
public class AuditLogController {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogController.class);
    private static final List<String> CSV_HEADERS = List.of("ID", "დრო", "ვინ", "ქმედება", "ობიექტი", "დეტალები");
    private static final DateTimeFormatter CSV_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper META_AUDIT_MAPPER = new ObjectMapper();

    private final AuditChainService auditChainService;
    private final AuditLogQueryService auditLogQueryService;
    private final AuditLogRepository auditLogRepository;
    private final PermissionChecker permissionChecker;
    private final UserRepository userRepository;

    public AuditLogController(AuditChainService auditChainService, AuditLogQueryService auditLogQueryService,
            AuditLogRepository auditLogRepository, PermissionChecker permissionChecker,
            UserRepository userRepository) {
        this.auditChainService = auditChainService;
        this.auditLogQueryService = auditLogQueryService;
        this.auditLogRepository = auditLogRepository;
        this.permissionChecker = permissionChecker;
        this.userRepository = userRepository;
    }

    @GetMapping("/api/audit-logs")
    public ResponseEntity<?> list(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "user_name", required = false) String userName,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAudit(user);
        if (denial != null) {
            return denial;
        }

        int clampedLimit = Math.max(1, Math.min(limit, 200));
        List<String> scopeDepartments = scopeDepartments(user);
        AuditLogFilter filter = new AuditLogFilter(startDate, endDate, userId, userName, action, category, q);

        AuditLogQueryService.Page page = auditLogQueryService.list(filter, scopeDepartments, clampedLimit, offset);

        LinkedHashMap<String, Object> metaFilters = new LinkedHashMap<>();
        metaFilters.put("start_date", startDate);
        metaFilters.put("end_date", endDate);
        metaFilters.put("user_id", userId);
        metaFilters.put("user_name", userName);
        metaFilters.put("action", action);
        metaFilters.put("category", category);
        metaFilters.put("q", q);
        metaFilters.put("offset", offset);
        metaFilters.put("limit", clampedLimit);
        metaFilters.put("result_count", page.rows().size());
        metaFilters.put("scope_department", scopeDepartments == null ? null : String.join(", ", scopeDepartments));
        writeMetaAudit(user, "VIEW_AUDIT_LOG", metaFilters);

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.totalCount()))
                .body(page.rows());
    }

    /**
     * Writes directly to {@link HttpServletResponse} on the request thread,
     * rather than returning a {@link StreamingResponseBody} for Spring to
     * dispatch on a separate async-executor thread. Both stream a JDBC
     * cursor without materializing all rows in memory (the actual
     * memory-safety goal, matching export_audit_logs' generator +
     * yield_per(1000)); this form just gets there without the async-thread
     * hop, whose separate JDBC connection can't observe the current
     * request's in-flight transaction (integration tests wrap each test in
     * one @Transactional, uncommitted-and-rolled-back transaction).
     */
    @GetMapping("/api/audit-logs/export")
    public void export(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "user_name", required = false) String userName,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @AuthenticationPrincipal User user,
            HttpServletResponse httpResponse) throws IOException {
        ResponseEntity<Map<String, String>> denial = requireSystemAuditNonManager(user);
        if (denial != null) {
            httpResponse.setStatus(denial.getStatusCode().value());
            httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            httpResponse.getOutputStream().write(META_AUDIT_MAPPER.writeValueAsBytes(denial.getBody()));
            return;
        }

        AuditLogFilter filter = new AuditLogFilter(startDate, endDate, userId, userName, action, category, q);

        // Written before streaming starts, matching Python: one meta-audit row per
        // export request regardless of how many CSV rows end up streamed out.
        LinkedHashMap<String, Object> metaFilters = new LinkedHashMap<>();
        metaFilters.put("start_date", startDate);
        metaFilters.put("end_date", endDate);
        metaFilters.put("user_id", userId);
        metaFilters.put("user_name", userName);
        metaFilters.put("action", action);
        metaFilters.put("category", category);
        metaFilters.put("q", q);
        writeMetaAudit(user, "EXPORT_AUDIT_LOG", metaFilters);

        httpResponse.setContentType("text/csv; charset=UTF-8");
        httpResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit_logs.csv");
        Writer writer = new OutputStreamWriter(httpResponse.getOutputStream(), StandardCharsets.UTF_8);
        writer.write(CsvExportBuilder.buildHeaderRow(CSV_HEADERS));
        auditLogQueryService.streamForExport(filter, row -> {
            try {
                String itemName = row.itemName() != null ? row.itemName() : row.itemType();
                List<Object> cells = List.of(
                        row.id(),
                        row.timestamp() != null ? row.timestamp().format(CSV_TIMESTAMP_FMT) : "",
                        row.adminName(),
                        row.action(),
                        itemName,
                        row.details() != null ? row.details() : "");
                writer.write(CsvExportBuilder.buildDataRow(cells));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        writer.flush();
    }

    @GetMapping("/api/audit-logs/{id}/verify")
    public ResponseEntity<?> verify(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAuditNonManager(user);
        if (denial != null) {
            return denial;
        }
        return auditChainService.verify(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "ჩანაწერი ვერ მოიძებნა")));
    }

    @GetMapping("/api/audit-logs/chain-health")
    public ResponseEntity<?> chainHealth(
            @RequestParam(defaultValue = "100") int n, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAuditNonManager(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(auditChainService.chainHealth(n));
    }

    /**
     * The department strings a caller's audit-log view is pinned to.
     * {@code null} = unrestricted (system_admin, content_admin); an empty
     * list = pinned to nothing, i.e. no rows.
     *
     * <p>Was a single exact-match string, with two problems fixed together
     * (audit SEC-13). It under-included a parent-department manager
     * ("ტექნიკური") to zero rows while their operators sit in
     * "ტექნიკური — ჯგუფი 03"; and a manager whose {@code department} was
     * null returned null from here, which this method's own caller reads as
     * <i>unrestricted</i> -- so the least well-defined account got the
     * widest view. Both follow from {@link ManagerScope}: the visible users
     * are resolved with the prefix-aware rule, their departments are what
     * the query pins to, and an unassigned manager resolves to an empty
     * list rather than to null.
     *
     * <p>Pins to the resolved departments rather than to the resolved user
     * ids on purpose: the set is a handful of strings regardless of
     * headcount, where an id list would grow with the team and run at
     * Oracle's 1000-element {@code IN} limit.
     */
    private List<String> scopeDepartments(User user) {
        if (!ManagerScope.isDepartmentScoped(user)) {
            return null;
        }
        return ManagerScope.visibleActiveUsers(userRepository.findByActiveTrue(), user).stream()
                .map(User::getDepartment)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Best-effort meta-audit write ("someone looked at / exported the audit
     * trail, with which filters") -- mirrors _log_audit_trail_access
     * (routers/audit_logs.py:81-97) exactly, including swallowing any
     * failure so it can never block the read the caller already computed.
     */
    private void writeMetaAudit(User actor, String action, LinkedHashMap<String, Object> filters) {
        try {
            LinkedHashMap<String, Object> nonEmpty = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                Object value = entry.getValue();
                if (value != null && !"".equals(value)) {
                    nonEmpty.put(entry.getKey(), value);
                }
            }
            AuditLog metaLog = new AuditLog();
            metaLog.setAdminId(actor.getId());
            metaLog.setAction(action);
            metaLog.setItemType("audit_log");
            metaLog.setItemId(actor.getId());
            metaLog.setTimestamp(TbilisiTime.now());
            metaLog.setDetails(META_AUDIT_MAPPER.writeValueAsString(nonEmpty));
            auditLogRepository.save(metaLog);
        } catch (Exception e) {
            logger.error("Failed to write meta-audit row for {} by adminId={}", action, actor.getId(), e);
        }
    }

    private ResponseEntity<Map<String, String>> requireSystemAudit(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        if (!permissionChecker.hasPermission(user, Permission.SYSTEM_AUDIT)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireSystemAuditNonManager(User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAudit(user);
        if (denial != null) {
            return denial;
        }
        if (user.getRole() == Role.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "ეს ფუნქცია ხელმისაწვდომია მხოლოდ ადმინისტრატორებისთვის"));
        }
        return null;
    }
}
