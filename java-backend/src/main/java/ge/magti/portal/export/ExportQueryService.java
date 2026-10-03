package ge.magti.portal.export;

import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.compliance.MandatoryReach;
import ge.magti.portal.content.ItemDetail;
import ge.magti.portal.content.ItemKey;
import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.ManagerScope;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.stats.ComplianceRecord;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * DB-query half of the readings exports (CSV, XLSX and PDF).
 * Batch-fetches User/RequiredReading rather than a
 * multi-entity JPQL join, using the same N+1-avoidance-via-map idiom used
 * throughout this port.
 *
 * <p><b>User-approved fix, 2026-08-06:</b> the original CSV export scoped to
 * the compliance calculation's eligible user ids (active, non-management)
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

    /** Phase 3 shadow only; nullable so the DB-free scoping tests stay unchanged. */
    private final ScopeResolver scopeResolver;

    /** Production complete-result boundary; nullable only in the DB-free compatibility constructor. */
    private final UserDirectoryQueryService userDirectoryQueryService;

    /**
     * Which readings are in force (PO-40), for the unread and overdue rows.
     * Null only in the DB-free compatibility constructors, which then export
     * confirmations alone, as before.
     */
    private final MandatoryReach mandatoryReach;

    /**
     * The material's title and the person's group, two of PO-13's columns.
     * Null only in the DB-free compatibility constructors, which then fall
     * back to the title kept on the reading and an empty group.
     */
    private final ItemTitleResolver itemTitleResolver;
    private final TeamRepository teamRepository;

    public ExportQueryService(
            ComplianceQueryService complianceQueryService, ReadStatusRepository readStatusRepository,
            UserRepository userRepository, RequiredReadingRepository requiredReadingRepository) {
        this(complianceQueryService, readStatusRepository, userRepository, requiredReadingRepository, null, null, null);
    }

    public ExportQueryService(
            ComplianceQueryService complianceQueryService, ReadStatusRepository readStatusRepository,
            UserRepository userRepository, RequiredReadingRepository requiredReadingRepository,
            ScopeResolver scopeResolver,
            UserDirectoryQueryService userDirectoryQueryService) {
        this(complianceQueryService, readStatusRepository, userRepository, requiredReadingRepository,
                scopeResolver, userDirectoryQueryService, null);
    }

    public ExportQueryService(
            ComplianceQueryService complianceQueryService, ReadStatusRepository readStatusRepository,
            UserRepository userRepository, RequiredReadingRepository requiredReadingRepository,
            ScopeResolver scopeResolver,
            UserDirectoryQueryService userDirectoryQueryService,
            MandatoryReach mandatoryReach) {
        this(complianceQueryService, readStatusRepository, userRepository, requiredReadingRepository,
                scopeResolver, userDirectoryQueryService, mandatoryReach, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExportQueryService(
            ComplianceQueryService complianceQueryService, ReadStatusRepository readStatusRepository,
            UserRepository userRepository, RequiredReadingRepository requiredReadingRepository,
            ScopeResolver scopeResolver,
            UserDirectoryQueryService userDirectoryQueryService,
            MandatoryReach mandatoryReach,
            ItemTitleResolver itemTitleResolver,
            TeamRepository teamRepository) {
        this.itemTitleResolver = itemTitleResolver;
        this.teamRepository = teamRepository;
        this.mandatoryReach = mandatoryReach;
        this.complianceQueryService = complianceQueryService;
        this.readStatusRepository = readStatusRepository;
        this.userRepository = userRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.scopeResolver = scopeResolver;
        this.userDirectoryQueryService = userDirectoryQueryService;
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
        List<User> active = boundedActiveUsers();
        List<User> visible;
        if (scopeResolver == null) {
            visible = ManagerScope.visibleActiveUsers(active, caller);
        } else {
            var primaryScope = scopeResolver.resolvePrimaryLeadership(caller);
            visible = active.stream().filter(candidate -> primaryScope.includesTeam(candidate.getTeamId())).toList();
        }
        List<Long> ids = visible.stream().map(User::getId).toList();
        return complianceQueryService.computeCompliance(ids, null);
    }

    private List<User> boundedActiveUsers() {
        // Production is always database-bounded. The fallback exists only for
        // the older repository-mock constructor used by DB-free pure tests.
        return userDirectoryQueryService == null
                ? userRepository.findByActiveTrue()
                : userDirectoryQueryService.listActiveUsersWithinLimit();
    }

    /**
     * Every ReadStatus row for an eligible (active, non-management) user
     * within {@code caller}'s permitted scope, flattened with its
     * User/RequiredReading fields. Throws {@link ExportTooLargeException}
     * past {@link ExportSizeGuard#MAX_ROWS} -- synchronously, in the request
     * handler, before any background job is enqueued.
     */
    public List<ReadingExportRow> eligibleReadingRows(User caller) {
        return eligibleReadingRows(caller, null, null);
    }

    /**
     * The same rows, limited to readings whose deadline falls between
     * {@code from} and {@code through} (whole Tbilisi days, either end open
     * when null).
     *
     * <p>Without a period the file grows with the portal's whole history --
     * people times every mandatory item ever assigned -- and at 600
     * operators it passes {@link ExportSizeGuard#MAX_ROWS} after about 34
     * items, after which no leader could export anything at all and the
     * refusal asked them to narrow a filter that did not exist (big-export
     * check, 2026-10-02: 22,752 rows). The owner chose a period over a
     * higher ceiling.
     */
    public List<ReadingExportRow> eligibleReadingRows(User caller, LocalDate from, LocalDate through) {
        List<Long> eligibleUserIds = scopedCompliance(caller).stream()
                .map(record -> record.user().getId())
                .toList();
        if (eligibleUserIds.isEmpty()) {
            return List.of();
        }

        PageRequest firstPastTheCap = PageRequest.of(0, ExportSizeGuard.MAX_ROWS + 1, Sort.by("id"));
        boolean bounded = from != null || through != null;
        OffsetDateTime dueFrom = startOfDay(from == null ? EARLIEST : from);
        OffsetDateTime dueBefore = startOfDay((through == null ? LATEST : through).plusDays(1));
        List<ReadStatus> statuses = bounded
                ? readStatusRepository.findByUserIdInAndDueDateWithin(eligibleUserIds, dueFrom, dueBefore, firstPastTheCap)
                : readStatusRepository.findByUserIdIn(eligibleUserIds, firstPastTheCap);
        ExportSizeGuard.checkSize(statuses.size());

        Map<Long, User> usersById = userRepository.findAllById(eligibleUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        List<Long> readingIds = statuses.stream().map(ReadStatus::getRequiredReadingId).distinct().toList();
        Map<Long, RequiredReading> readingsById = requiredReadingRepository.findAllById(readingIds).stream()
                .collect(Collectors.toMap(RequiredReading::getId, r -> r));

        List<Confirmed> confirmed = new ArrayList<>();
        for (ReadStatus status : statuses) {
            User user = usersById.get(status.getUserId());
            RequiredReading reading = readingsById.get(status.getRequiredReadingId());
            if (user != null && reading != null) {
                confirmed.add(new Confirmed(user, reading, status));
            }
        }
        List<Outstanding> outstanding = mandatoryReach == null ? List.of()
                : outstandingRows(usersById.values(), statuses, dueFrom, dueBefore);
        ExportSizeGuard.checkSize(confirmed.size() + outstanding.size());

        Set<RequiredReading> readings = new HashSet<>();
        confirmed.forEach(c -> readings.add(c.reading()));
        outstanding.forEach(o -> readings.add(o.reading()));
        Map<ItemKey, ItemDetail> details = itemTitleResolver == null ? Map.of()
                : itemTitleResolver.resolveDetailsBulk(readings.stream()
                        .map(r -> new ItemKey(r.getItemType(), r.getItemId())).distinct().toList());
        Map<Long, String> groups = teamNames(usersById.values());

        List<ReadingExportRow> rows = new ArrayList<>();
        for (Confirmed c : confirmed) {
            OffsetDateTime readAt = c.status().getReadAt();
            OffsetDateTime due = c.reading().getDueDate();
            // PO-13's third status. A confirmation is never refused for
            // coming late; the file says that it did.
            String status = "read".equals(c.status().getStatus()) && readAt != null && due != null && readAt.isAfter(due)
                    ? "late" : c.status().getStatus();
            rows.add(row(c.user(), c.reading(), status, readAt, details, groups));
        }
        for (Outstanding o : outstanding) {
            rows.add(row(o.user(), o.reading(), o.status(), null, details, groups));
        }
        rows.sort(Comparator.comparing(ReadingExportRow::userName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReadingExportRow::dueDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** No deadline is earlier or later than these; they stand in for an open end. */
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2100, 12, 31);

    private static OffsetDateTime startOfDay(LocalDate day) {
        return day.atStartOfDay().atOffset(TbilisiTime.OFFSET);
    }

    private record Confirmed(User user, RequiredReading reading, ReadStatus status) {
    }

    private record Outstanding(User user, RequiredReading reading, String status) {
    }

    private static ReadingExportRow row(
            User user, RequiredReading reading, String status, OffsetDateTime readAt,
            Map<ItemKey, ItemDetail> details, Map<Long, String> groups) {
        ItemDetail detail = details.get(new ItemKey(reading.getItemType(), reading.getItemId()));
        // A purged item keeps the title its reading was given (PO-27's evidence).
        String title = detail != null ? detail.title() : reading.getItemTitleSnapshot();
        return new ReadingExportRow(
                user.getId(), user.getName(), user.getDepartment(),
                user.getTeamId() == null ? null : groups.get(user.getTeamId()),
                reading.getItemType(), reading.getItemId(), title, status, readAt, reading.getDueDate());
    }

    private Map<Long, String> teamNames(java.util.Collection<User> users) {
        Set<Long> teamIds = users.stream().map(User::getTeamId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (teamRepository == null || teamIds.isEmpty()) {
            return Map.of();
        }
        return teamRepository.findAllById(teamIds).stream().collect(Collectors.toMap(Team::getId, Team::getName));
    }

    /**
     * What each person still owes: every reading in force for their
     * department (the same addressing and PO-40 rule as
     * {@code GET /api/compliance/my-readings}) with no confirmation, as
     * "unread", or "overdue" once its deadline has passed.
     *
     * <p>The export used to be built from confirmations alone, so it listed
     * who HAD read and left out exactly the people a leader exports it to
     * find -- while PO-13 promises a status column of read / unread / late
     * (simulation, 2026-10-01).
     */
    private List<Outstanding> outstandingRows(
            java.util.Collection<User> users, List<ReadStatus> confirmed,
            OffsetDateTime dueFrom, OffsetDateTime dueBefore) {
        Set<String> targets = new LinkedHashSet<>();
        for (User user : users) {
            targets.addAll(DepartmentMatcher.visibilityTargets(user.getDepartment()));
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        List<RequiredReading> addressed = CompleteResultGuard.enforce(requiredReadingRepository
                .findByTargetDepartmentIn(new ArrayList<>(targets), CompleteResultGuard.sentinelPage()));
        Set<Long> inForce = mandatoryReach.inForceIds(addressed);
        Set<String> done = new HashSet<>();
        for (ReadStatus status : confirmed) {
            done.add(status.getUserId() + ":" + status.getRequiredReadingId());
        }
        OffsetDateTime now = TbilisiTime.now();
        List<Outstanding> rows = new ArrayList<>();
        for (User user : users) {
            List<String> own = DepartmentMatcher.visibilityTargets(user.getDepartment());
            for (RequiredReading reading : addressed) {
                if (!inForce.contains(reading.getId()) || !own.contains(reading.getTargetDepartment())
                        || done.contains(user.getId() + ":" + reading.getId())
                        || !within(reading.getDueDate(), dueFrom, dueBefore)) {
                    continue;
                }
                boolean late = reading.getDueDate() != null && reading.getDueDate().isBefore(now);
                rows.add(new Outstanding(user, reading, late ? "overdue" : "unread"));
                if (rows.size() > ExportSizeGuard.MAX_ROWS) {
                    return rows; // the caller refuses it; no need to build the rest
                }
            }
        }
        return rows;
    }

    private static boolean within(OffsetDateTime due, OffsetDateTime from, OffsetDateTime before) {
        return due != null && !due.isBefore(from) && due.isBefore(before);
    }

    /**
     * The team-stats PDF's department aggregation:
     * {@code department -> {totalRequired, totalRead}}, alphabetically
     * sorted so the caller doesn't have to. Scoped to {@code caller}
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
