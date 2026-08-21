package ge.magti.portal.export;

import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.ManagerScope;
import ge.magti.portal.stats.ComplianceRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * DB-query half of the readings exports (routers/exports.py's
 * export_readings/export_readings_xlsx/export_readings_pdf, lines
 * 93-98,144-150,264-270). Batch-fetches User/RequiredReading rather than a
 * multi-entity JPQL join, same N+1-avoidance-via-map idiom used throughout
 * this port (e.g. MessagingController).
 *
 * <p><b>User-approved fix, 2026-08-06:</b> Python's CSV export scopes to
 * {@code compute_compliance()}'s eligible user ids (active, non-management)
 * but the xlsx/pdf exports never did -- a live gap where the same personal
 * data got less protection depending on file format. This service is the
 * single query path for all 3 formats now, so that gap can't reopen.
 *
 * <p><b>SEC-02 fix (audit OPUS5-1):</b> both query methods used to be
 * org-wide with no caller argument at all, while {@code reports.export} is a
 * {@link Role#MANAGER} default ({@link ge.magti.portal.domain.Permission
 * #defaultsFor}) -- so any group manager could download the name, department
 * and per-item compliance status of every employee in the company. Both now
 * take the caller and route through {@link #scopedCompliance}, applying the
 * same rule bug #312 already established in
 * {@code StatsController.getCriticalOperators}: unscoped only for
 * SYSTEM_ADMIN and hard-pinned to the caller's own department for every
 * other role. The caller argument is mandatory rather than an
 * overload precisely so no unscoped call path can be reintroduced by
 * accident.
 */
@Service
public class ExportQueryService {

    private final ComplianceQueryService complianceQueryService;
    private final ReadStatusRepository readStatusRepository;
    private final UserRepository userRepository;
    private final RequiredReadingRepository requiredReadingRepository;

    public ExportQueryService(
            ComplianceQueryService complianceQueryService, ReadStatusRepository readStatusRepository,
            UserRepository userRepository, RequiredReadingRepository requiredReadingRepository) {
        this.complianceQueryService = complianceQueryService;
        this.readStatusRepository = readStatusRepository;
        this.userRepository = userRepository;
        this.requiredReadingRepository = requiredReadingRepository;
    }

    /**
     * True when this caller's exports must be pinned to their own department,
     * false only for SYSTEM_ADMIN. Permissions grant the export action; they
     * never create an org-wide data scope.
     *
     * <p><b>This is the inner of two layers, not the whole rule.</b>
     * {@code ExportController.requireReportsExport} refuses any caller that
     * does not {@link ManagerScope#holdsEmployeeDataScope} before reaching
     * this service, so in production the scoped branch below only ever runs
     * for a MANAGER. Pinning every non-admin caller rather than only MANAGER
     * is deliberate anyway: it means a future call path that forgets the
     * controller gate degrades to one department instead of to the whole
     * company.
     *
     * <p>Deliberately separate from {@link #scopeDepartmentFor} rather than
     * inferred from it being null: a manager whose {@code department} is null
     * is still department-scoped, and collapsing the two would make that case
     * fall through to the org-wide branch -- i.e. re-open SEC-02 for exactly
     * the accounts whose scope is least well defined.
     */
    public static boolean isDepartmentScoped(User caller) {
        return caller != null && caller.getRole() != Role.SYSTEM_ADMIN;
    }

    /**
     * The caller's effective export scope: their own department string when
     * {@link #isDepartmentScoped}, otherwise null meaning unrestricted --
     * the same shape and wording as {@code AuditLogController.scopeDepartment},
     * so the two department-scope decisions in the codebase stay readable as
     * one rule. Public because {@code ExportController} records this exact
     * value as the export audit row's {@code scope_department}. Null here is
     * only meaningful alongside {@link #isDepartmentScoped}.
     */
    public static String scopeDepartmentFor(User caller) {
        return isDepartmentScoped(caller) ? caller.getDepartment() : null;
    }

    /**
     * Compliance records the caller is allowed to export. Resolves the
     * caller's visible users through {@link ManagerScope} and passes them as
     * {@code computeCompliance(ids, null)} -- the same two lines
     * {@code StatsController.getCriticalOperators} runs, so a future reader
     * checking whether exports match Stats can diff them rather than
     * re-derive the equivalence.
     *
     * <p>An earlier version of this comment said the SEC-13 under-inclusion
     * was left in place here on purpose, "because fixing it would widen
     * access, which is not this fix's job". That was the right call for the
     * SEC-02 commit and the wrong state to leave the code in: it meant a
     * parent-department manager exported an empty file. SEC-13 is now fixed
     * at the rule instead of at each call site, and this reads the rule.
     * A manager with a null, blank or "All" department still resolves to
     * zero users -- fail closed, and now explicitly rather than by accident.
     */
    private List<ComplianceRecord> scopedCompliance(User caller) {
        if (caller == null) {
            return List.of();
        }
        if (caller.getRole() == Role.SYSTEM_ADMIN) {
            return complianceQueryService.computeCompliance();
        }
        List<Long> ids = ManagerScope.visibleActiveUsers(userRepository.findByActiveTrue(), caller).stream()
                .map(User::getId)
                .toList();
        return complianceQueryService.computeCompliance(ids, null);
    }

    /**
     * Every ReadStatus row for an eligible (active, non-management) user
     * within {@code caller}'s permitted scope, flattened with its
     * User/RequiredReading fields. Throws {@link ExportTooLargeException}
     * past {@link ExportSizeGuard#MAX_ROWS} -- mirrors Python calling
     * {@code _guard_export_size} synchronously in the request handler,
     * before any background job is enqueued.
     */
    public List<ReadingExportRow> eligibleReadingRows(User caller) {
        List<Long> eligibleUserIds = scopedCompliance(caller).stream()
                .map(record -> record.user().getId())
                .toList();
        if (eligibleUserIds.isEmpty()) {
            return List.of();
        }

        List<ReadStatus> statuses = readStatusRepository.findByUserIdIn(eligibleUserIds);
        ExportSizeGuard.checkSize(statuses.size());

        Map<Long, User> usersById = userRepository.findAllById(eligibleUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        List<Long> readingIds = statuses.stream().map(ReadStatus::getRequiredReadingId).distinct().toList();
        Map<Long, RequiredReading> readingsById = requiredReadingRepository.findAllById(readingIds).stream()
                .collect(Collectors.toMap(RequiredReading::getId, r -> r));

        List<ReadingExportRow> rows = new ArrayList<>();
        for (ReadStatus status : statuses) {
            User user = usersById.get(status.getUserId());
            RequiredReading reading = readingsById.get(status.getRequiredReadingId());
            if (user == null || reading == null) {
                continue;
            }
            rows.add(new ReadingExportRow(
                    status.getUserId(), user.getName(), user.getDepartment(),
                    reading.getItemType(), reading.getItemId(), status.getStatus(),
                    status.getReadAt(), reading.getDueDate()));
        }
        return rows;
    }

    /**
     * Mirrors export_team_stats_pdf's department aggregation
     * (routers/exports.py:305-309): {@code department -> {totalRequired,
     * totalRead}}, alphabetically sorted (Python's {@code sorted(by_dept
     * .items())}) so the caller doesn't have to. Scoped to {@code caller}
     * per SEC-02, so a manager's team-stats PDF is a one-row table for their
     * own department rather than a company-wide league table.
     */
    public SortedMap<String, int[]> departmentComplianceTotals(User caller) {
        SortedMap<String, int[]> byDept = new TreeMap<>();
        for (var record : scopedCompliance(caller)) {
            String department = record.user().getDepartment();
            String key = (department == null || department.isBlank()) ? "—" : department;
            int[] bucket = byDept.computeIfAbsent(key, k -> new int[2]);
            bucket[0] += record.progress().requiredCount();
            bucket[1] += record.progress().readCount();
        }
        return byDept;
    }
}
