package ge.magti.portal.web;

import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.compliance.MandatoryReach;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.security.ManagerScope;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.stats.ComplianceRecord;
import ge.magti.portal.stats.CriticalOperator;
import ge.magti.portal.stats.DepartmentBuckets;
import ge.magti.portal.stats.DepartmentDashboard;
import ge.magti.portal.stats.DepartmentStatsBuilder;
import ge.magti.portal.stats.GroupMemberCompletion;
import ge.magti.portal.stats.OperatorStatsBuilder;
import ge.magti.portal.stats.TeamMemberCompletion;
import ge.magti.portal.stats.TeamStatsBuilder;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.util.DepartmentGroup;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * All 12 statistics/dashboard endpoints. Reuses
 * {@link ComplianceQueryService} (the single compliance
 * implementation, also used by Compliance) plus the DB-free Stats builders
 * (DepartmentStatsBuilder, OperatorStatsBuilder, TeamStatsBuilder) that were
 * built ahead of this controller.
 *
 * <p><b>No stats cache.</b> The original app had a best-effort Redis cache
 * -- a pure performance optimisation with an always-safe fallback (a cache
 * miss just re-runs the same DB query). Adding one would mean building
 * Spring cache/Redis infrastructure this codebase doesn't have, for zero
 * behavioural difference. Every endpoint here always does the "cache miss"
 * path.
 */
@RestController
public class StatsController {

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    private final ComplianceQueryService complianceQueryService;
    private final UserRepository userRepository;
    private final SearchLogRepository searchLogRepository;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final ArticleRepository articleRepository;
    private final VideoInstructionRepository videoInstructionRepository;
    private final AuditLogRepository auditLogRepository;
    private final ArticleViewLogRepository articleViewLogRepository;
    private final ScopeResolver scopeResolver;
    private final PermissionChecker permissionChecker;
    private final UserDirectoryQueryService userDirectoryQueryService;
    private final MandatoryReach mandatoryReach;

    public StatsController(
            ComplianceQueryService complianceQueryService,
            UserRepository userRepository,
            SearchLogRepository searchLogRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            ArticleRepository articleRepository,
            VideoInstructionRepository videoInstructionRepository,
            AuditLogRepository auditLogRepository,
            ArticleViewLogRepository articleViewLogRepository) {
        this(complianceQueryService, userRepository, searchLogRepository, requiredReadingRepository,
                readStatusRepository, articleRepository, videoInstructionRepository, auditLogRepository,
                articleViewLogRepository, null, new PermissionChecker(), null);
    }

    /** Phase 3 shadow only; the nine-argument constructor above keeps the DB-free tests unchanged. */
    public StatsController(
            ComplianceQueryService complianceQueryService,
            UserRepository userRepository,
            SearchLogRepository searchLogRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            ArticleRepository articleRepository,
            VideoInstructionRepository videoInstructionRepository,
            AuditLogRepository auditLogRepository,
            ArticleViewLogRepository articleViewLogRepository,
            ScopeResolver scopeResolver,
            PermissionChecker permissionChecker,
            UserDirectoryQueryService userDirectoryQueryService) {
        this(complianceQueryService, userRepository, searchLogRepository, requiredReadingRepository,
                readStatusRepository, articleRepository, videoInstructionRepository, auditLogRepository,
                articleViewLogRepository, scopeResolver, permissionChecker, userDirectoryQueryService, null);
    }

    /** PO-40 adds the in-force rule the KPI counts by; the two constructors above keep the DB-free tests unchanged. */
    @org.springframework.beans.factory.annotation.Autowired
    public StatsController(
            ComplianceQueryService complianceQueryService,
            UserRepository userRepository,
            SearchLogRepository searchLogRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            ArticleRepository articleRepository,
            VideoInstructionRepository videoInstructionRepository,
            AuditLogRepository auditLogRepository,
            ArticleViewLogRepository articleViewLogRepository,
            ScopeResolver scopeResolver,
            PermissionChecker permissionChecker,
            UserDirectoryQueryService userDirectoryQueryService,
            MandatoryReach mandatoryReach) {
        this.mandatoryReach = mandatoryReach;
        this.scopeResolver = scopeResolver;
        this.complianceQueryService = complianceQueryService;
        this.userRepository = userRepository;
        this.searchLogRepository = searchLogRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.articleRepository = articleRepository;
        this.videoInstructionRepository = videoInstructionRepository;
        this.auditLogRepository = auditLogRepository;
        this.articleViewLogRepository = articleViewLogRepository;
        this.permissionChecker = permissionChecker;
        this.userDirectoryQueryService = userDirectoryQueryService;
    }

    @GetMapping("/api/statistics/popular-searches")
    public ResponseEntity<?> getPopularSearches(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireStatsView(user);
        if (denial != null) {
            return denial;
        }
        List<PopularSearchResponse> results = searchLogRepository.popularSearchTerms(PageRequest.of(0, 10)).stream()
                .map(row -> new PopularSearchResponse((String) row[0], ((Number) row[1]).longValue()))
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/api/statistics/failed-searches")
    public ResponseEntity<?> getFailedSearches(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireStatsView(user);
        if (denial != null) {
            return denial;
        }
        List<PopularSearchResponse> results = searchLogRepository.failedSearchTerms(PageRequest.of(0, 10)).stream()
                .map(row -> new PopularSearchResponse((String) row[0], ((Number) row[1]).longValue()))
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/api/statistics/compliance")
    public ResponseEntity<?> getComplianceStatistics(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireStatsView(user);
        if (denial != null) {
            return denial;
        }
        List<ComplianceRecord> records = complianceQueryService.computeCompliance();
        int readCount = records.stream().mapToInt(r -> r.progress().readCount()).sum();
        int totalAssignments = records.stream().mapToInt(r -> r.progress().requiredCount()).sum();

        double readPercentage;
        double unreadPercentage;
        if (totalAssignments > 0) {
            readPercentage = roundHalfEven((readCount / (double) totalAssignments) * 100, 2);
            unreadPercentage = roundHalfEven(100 - readPercentage, 2);
        } else {
            readPercentage = 0.0;
            unreadPercentage = 100.0;
        }

        List<Object[]> topRows = requiredReadingRepository.topReadArticleIds(
                ComplianceCalculator.MANAGEMENT_ROLES, PageRequest.of(0, 5));
        List<Long> topIds = new ArrayList<>();
        Map<Long, Long> readCountsByArticleId = new LinkedHashMap<>();
        for (Object[] row : topRows) {
            Long articleId = ((Number) row[0]).longValue();
            topIds.add(articleId);
            readCountsByArticleId.put(articleId, ((Number) row[1]).longValue());
        }

        Map<Long, Article> articlesById = new LinkedHashMap<>();
        for (Article a : articleRepository.findAllById(topIds)) {
            articlesById.put(a.getId(), a);
        }

        List<TopArticleResponse> topArticles = new ArrayList<>();
        for (Long id : topIds) {
            Article a = articlesById.get(id);
            // A private draft can carry reads only if it was published before
            // its author withdrew it; its title is still theirs alone (PO-34).
            if (a != null && !ArticleVisibility.isPrivateDraftOfAnother(a, user)) {
                topArticles.add(new TopArticleResponse(a.getId(), a.getTitle(), readCountsByArticleId.get(id)));
            }
        }

        return ResponseEntity.ok(new ComplianceStatsResponse(readPercentage, unreadPercentage, topArticles));
    }

    /** System-admin only, unlike every other endpoint here. */
    @GetMapping("/api/statistics/user-progress")
    public ResponseEntity<?> getUserProgress(
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "1000") int limit,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        if (ListQueryBounds.isInvalid(skip, limit)) {
            return ResponseEntity.badRequest().body(Map.of("detail", ListQueryBounds.INVALID_DETAIL));
        }
        List<ComplianceRecord> records = complianceQueryService.computeComplianceForUsers(
                userDirectoryQueryService.listActiveOperators(skip, limit));
        List<UserProgressItemResponse> results = records.stream()
                .map(r -> new UserProgressItemResponse(
                        r.user().getId(), r.user().getName(), r.user().getDepartment(),
                        r.progress().readCount(), r.progress().requiredCount(), r.progress().percentage() + "%"))
                .sorted(Comparator.comparingInt((UserProgressItemResponse u) -> parsePercentage(u.percentage())).reversed())
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/api/admin/stats/team/{teamId}")
    public ResponseEntity<?> getAdminTeamStats(@PathVariable("teamId") Long teamId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        List<Long> ids = boundedActiveUsers().stream()
                .filter(candidate -> teamId.equals(candidate.getTeamId()))
                .map(User::getId).toList();
        List<ComplianceRecord> records = complianceQueryService.computeCompliance(ids, null);
        List<TeamMemberCompletion> members = TeamStatsBuilder.buildTeamMemberCompletions(records);
        int avg = TeamStatsBuilder.averagePercentage(records);
        return ResponseEntity.ok(new AdminTeamStatsResponse(teamId, avg + "%", members));
    }

    /**
     * <b>SEC-13 fix:</b> the manager branch matched departments by string
     * equality, so a manager stored as the bare parent ("ტექნიკური") whose
     * operators are stored as "ტექნიკური — ჯგუფი 03" got an empty team
     * screen rather than their team. Now uses {@link ManagerScope}, the same
     * prefix-aware rule direct messaging and content visibility already use.
     */
    @GetMapping("/api/manager/team-stats")
    public ResponseEntity<?> getTeamStats(
            @RequestParam(required = false) String department,
            @RequestParam(name = "team_id", required = false) Long teamId,
            @RequestParam(name = "operator_name", required = false) String operatorName,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireManagerOrAdmin(user);
        if (denial != null) {
            return denial;
        }

        String dept;
        List<User> candidates;
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            List<User> active = boundedActiveUsers();
            if (department != null && !department.isBlank()) {
                dept = department;
                candidates = active.stream().filter(candidate -> dept.equals(candidate.getDepartment())).toList();
            } else {
                dept = "All";
                candidates = active;
            }
        } else {
            // RBAC: a manager is hard-pinned to their own department, even if a
            // different one is sent in the query string. Prefix-aware, so a
            // parent-department manager sees their sub-groups and a sub-group
            // manager still sees only their own group (SEC-13).
            dept = user.getDepartment();
            List<User> active = boundedActiveUsers();
            List<ScopeResolver.LeadershipOption> options = scopeResolver == null
                    ? List.of() : scopeResolver.leadershipOptions(user);
            if (!options.isEmpty()) {
                Long selectedTeamId = teamId != null ? teamId : options.getFirst().teamId();
                if (!scopeResolver.resolveGroupLeadership(user).includesTeam(selectedTeamId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("detail", "არჩეული ჯგუფი თქვენს აქტიურ დანიშვნებში არ შედის"));
                }
                candidates = active.stream()
                        .filter(candidate -> selectedTeamId.equals(candidate.getTeamId()))
                        .toList();
            } else {
                candidates = shadowScope("scope.team-stats", user, active,
                        ManagerScope.visibleActiveUsers(active, user));
            }
        }

        if (teamId != null) {
            candidates = candidates.stream().filter(u -> teamId.equals(u.getTeamId())).toList();
        }
        if (operatorName != null && !operatorName.isBlank()) {
            String needle = operatorName.strip().toLowerCase();
            candidates = candidates.stream()
                    .filter(u -> u.getName() != null && u.getName().toLowerCase().contains(needle))
                    .toList();
        }

        List<Long> ids = candidates.stream().map(User::getId).toList();
        List<ComplianceRecord> records = complianceQueryService.computeCompliance(ids, null);
        List<TeamMemberCompletion> members = TeamStatsBuilder.buildTeamMemberCompletions(records);
        return ResponseEntity.ok(new TeamStatsResponse(dept, members));
    }

    /**
     * Phase-0 access fix: a manager receives only records allowed by
     * {@link ManagerScope}. Building the tree from that scoped record set and
     * removing the builder's empty whitelist placeholders means sibling
     * department/group rows are absent from the wire response, not merely
     * stripped of named members. SYSTEM_ADMIN keeps the org-wide dashboard.
     *
     * <p><b>This supersedes the SEC-03 fix (audit OPUS5-1, option (a),
     * user-decided 2026-08-14)</b> and deliberately gives a manager something
     * back, so the change is worth reading as a whole rather than as a
     * loosening. That fix kept the dashboard org-wide and blanked every
     * group's {@code members} list unconditionally, on the reasoning that the
     * leak was the per-person rows and that a department's overall percentage
     * is not personal data. The second half of that reasoning did not hold:
     * per-group {@code compliance}, {@code output_volume} and
     * {@code critical_count} for every sibling group in every department
     * survived the redaction, so a group leader still read across the whole
     * company. Scoping the query removes those rows at the source, which in
     * turn makes the blanket redaction unnecessary -- and product rule #13
     * (ORG_ACCESS_ARCHITECTURE_PLAN_KA.md §2) explicitly grants a group
     * leader the names and full statistics of their own group's members. So
     * the named rows return, for that scope only.
     *
     * <p>{@code DepartmentStatsBuilder.withoutMembers} and
     * {@code DepartmentGroupStats.withMembers} were deleted with the branch
     * that called them; this paragraph is where their decision record now
     * lives.
     */
    @GetMapping("/api/manager/department-stats")
    public ResponseEntity<?> getDepartmentStats(
            @RequestParam(name = "team_id", required = false) Long teamId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireManagerOrAdmin(user);
        if (denial != null) {
            return denial;
        }
        if (user.getRole() == Role.MANAGER) {
            List<User> active = boundedActiveUsers();
            List<User> visible;
            if (scopeResolver != null && !scopeResolver.leadershipOptions(user).isEmpty()) {
                Long selectedTeamId = teamId != null ? teamId : scopeResolver.leadershipOptions(user).getFirst().teamId();
                if (!scopeResolver.resolveGroupLeadership(user).includesTeam(selectedTeamId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("detail", "არჩეული ჯგუფი თქვენს აქტიურ დანიშვნებში არ შედის"));
                }
                visible = active.stream().filter(candidate -> selectedTeamId.equals(candidate.getTeamId())).toList();
            } else {
                visible = shadowScope("scope.department-stats", user, active,
                        ManagerScope.visibleActiveUsers(active, user));
            }
            List<Long> ids = visible.stream()
                    .map(User::getId)
                    .toList();
            List<ComplianceRecord> records = complianceQueryService.computeCompliance(ids, null);
            DepartmentDashboard scoped = DepartmentStatsBuilder.build(records, TbilisiTime.now());
            return ResponseEntity.ok(new DepartmentDashboard(
                    scoped.insights(),
                    scoped.departments().stream().filter(department -> !department.empty()).toList(),
                    scoped.generatedAt()));
        }
        return ResponseEntity.ok(DepartmentStatsBuilder.build(
                complianceQueryService.computeCompliance(), TbilisiTime.now()));
    }

    /** DB-free controller tests and internal callers use the default group. */
    public ResponseEntity<?> getDepartmentStats(User user) {
        return getDepartmentStats(null, user);
    }

    @GetMapping("/api/manager/leadership-options")
    public ResponseEntity<?> getLeadershipOptions(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireManagerOrAdmin(user);
        if (denial != null) return denial;
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            return ResponseEntity.ok(new LeadershipOptionsResponse(List.of(), null, true));
        }
        List<ScopeResolver.LeadershipOption> groups = scopeResolver == null
                ? List.of() : scopeResolver.leadershipOptions(user);
        Long defaultTeamId = groups.stream()
                .filter(group -> group.assignmentType() == ge.magti.portal.domain.AssignmentType.PRIMARY)
                .map(ScopeResolver.LeadershipOption::teamId)
                .findFirst()
                .orElse(groups.isEmpty() ? null : groups.getFirst().teamId());
        boolean canExport = scopeResolver != null && scopeResolver.hasPrimaryLeadership(user);
        return ResponseEntity.ok(new LeadershipOptionsResponse(groups, defaultTeamId, canExport));
    }

    /**
     * Deliberate fix vs. the original app: this endpoint (and
     * {@code getGroupUsers} below) gated on content_admin/system_admin only,
     * while the dashboard they're drilled
     * into from -- {@link #getDepartmentStats} -- gates on
     * manager/system_admin. A plain
     * manager could see the Executive Department Dashboard but got a 403
     * clicking either of its own interactive drill-downs (critical-operators
     * ribbon tile, any group row) -- not a
     * Java-port regression; fixed here since the dashboard's whole audience
     * is managers.
     *
     * <p><b>Bug #312 fix:</b> that manager-access fix opened this endpoint up
     * without ever scoping the query, so any manager got the exact same
     * org-wide, cross-department list as content_admin/system_admin --
     * confirmed live (docs/archive/migration/TEST_PLAN_AND_RESULTS.md §2.1, asymmetry #1): a
     * manager in "ტექნიკური — ჯგუფი 03" saw an overdue operator from
     * "ტექნიკური — ჯგუფი 01". Now hard-pinned to the calling manager's own
     * department via {@link ManagerScope}, the same rule {@link #getTeamStats}
     * uses. SYSTEM_ADMIN keeps the unscoped org-wide view; content-admin-only
     * callers are denied until assignment-backed scoping exists.
     *
     * <p>The pin was originally an exact string match, which under-included a
     * parent-department manager to zero rows -- audit SEC-13, fixed with the
     * shared rule rather than at each of its five call sites.
     */
    @GetMapping("/api/admin/critical-operators")
    public ResponseEntity<?> getCriticalOperators(
            @RequestParam(name = "team_id", required = false) Long teamId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireManagerOrAdmin(user);
        if (denial != null) {
            return denial;
        }
        List<ComplianceRecord> records;
        if (user.getRole() == Role.MANAGER) {
            List<User> active = boundedActiveUsers();
            List<User> visible = shadowScope("scope.critical-operators", user, active,
                    ManagerScope.visibleActiveUsers(active, user));
            if (teamId != null && scopeResolver != null) {
                if (!scopeResolver.resolveGroupLeadership(user).includesTeam(teamId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("detail", "არჩეული ჯგუფი თქვენს აქტიურ დანიშვნებში არ შედის"));
                }
                visible = active.stream().filter(candidate -> teamId.equals(candidate.getTeamId())).toList();
            }
            List<Long> ids = visible.stream()
                    .map(User::getId)
                    .toList();
            records = complianceQueryService.computeCompliance(ids, null);
        } else {
            records = complianceQueryService.computeCompliance();
        }
        List<CriticalOperator> operators = OperatorStatsBuilder.buildCriticalOperators(records);
        return ResponseEntity.ok(new CriticalOperatorsResponse(operators, operators.size(), TbilisiTime.now()));
    }

    public ResponseEntity<?> getCriticalOperators(User user) {
        return getCriticalOperators(null, user);
    }

    /**
     * Same manager-access fix as {@link #getCriticalOperators}.
     *
     * <p><b>Bug #312 fix:</b> {@code department}/{@code groupName} were
     * caller-controlled path params with no check against the requester's
     * own department -- confirmed live: a manager in "ტექნიკური"
     * successfully pulled "ოფისი — ჯგუფი 01"'s user list. A manager is now
     * rejected with 403 unless the requested (department, groupName) pair
     * resolves to their own department string. SYSTEM_ADMIN is unrestricted;
     * content-admin-only callers are denied until assignment-backed scoping
     * exists.
     */
    @GetMapping("/api/admin/departments/{department}/groups/{groupName}/users")
    public ResponseEntity<?> getGroupUsers(
            @PathVariable String department, @PathVariable("groupName") String groupName,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireManagerOrAdmin(user);
        if (denial != null) {
            return denial;
        }
        List<User> candidates = boundedActiveUsers().stream()
                .filter(ComplianceCalculator::isEligible)
                .toList();
        List<User> matched = OperatorStatsBuilder.filterUsersInGroup(candidates, department, groupName);
        if (user.getRole() == Role.MANAGER) {
            if (scopeResolver != null && !scopeResolver.leadershipOptions(user).isEmpty()) {
                Optional<Long> requestedTeamId = scopeResolver.resolveLegacyGroupPath(department, groupName);
                if (requestedTeamId.isEmpty()
                        || !scopeResolver.resolveGroupLeadership(user).includesTeam(requestedTeamId.get())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
                }
                // Free-text department strings are transitional data, not an
                // authorization identity. Keep only members bound to the
                // canonical team proven above; stale/null/foreign bindings
                // cannot leak through a path-string match.
                matched = matched.stream()
                        .filter(candidate -> requestedTeamId.get().equals(candidate.getTeamId()))
                        .toList();
            } else {
                DepartmentGroup ownGroup = DepartmentMatcher.splitGroup(user.getDepartment());
                String ownBucket = DepartmentBuckets.match(ownGroup.prefix());
                if (!Objects.equals(department, ownBucket) || !Objects.equals(groupName, ownGroup.groupLabel())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
                }
            }
        }
        List<Long> ids = matched.stream().map(User::getId).toList();
        List<ComplianceRecord> records = complianceQueryService.computeCompliance(ids, null);
        List<GroupMemberCompletion> users = OperatorStatsBuilder.buildGroupUserCompletions(records);
        return ResponseEntity.ok(new GroupUsersResponse(department, groupName, users, users.size()));
    }

    @GetMapping("/api/statistics/activity")
    public ResponseEntity<?> getActivityTrend(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "day") String bucket,
            @RequestParam(required = false) String category,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireStatsView(user);
        if (denial != null) {
            return denial;
        }
        if (!"day".equals(bucket) && !"hour".equals(bucket)) {
            return ResponseEntity.badRequest().body(Map.of("detail", "bucket must be 'day' or 'hour'"));
        }
        int clampedDays = Math.max(1, Math.min(days, 90));
        String categoryUpper = (category == null || category.isBlank()) ? null : category.toUpperCase();

        OffsetDateTime now = TbilisiTime.now();
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> series = new ArrayList<>();

        if ("hour".equals(bucket)) {
            OffsetDateTime cutoff = now.withMinute(0).withSecond(0).withNano(0).minusHours((long) clampedDays * 24 - 1);
            mergeCounts(counts, auditLogRepository.countByHourBucket(cutoff, categoryUpper));
            if (categoryUpper == null || "USER".equals(categoryUpper)) {
                mergeCounts(counts, articleViewLogRepository.countByHourBucket(cutoff));
            }
            for (int i = 0; i < clampedDays * 24; i++) {
                series.add(cutoff.plusHours(i).format(HOUR_KEY));
            }
        } else {
            OffsetDateTime cutoff = now.withHour(0).withMinute(0).withSecond(0).withNano(0).minusDays((long) clampedDays - 1);
            mergeCounts(counts, auditLogRepository.countByDayBucket(cutoff, categoryUpper));
            if (categoryUpper == null || "USER".equals(categoryUpper)) {
                mergeCounts(counts, articleViewLogRepository.countByDayBucket(cutoff));
            }
            for (int i = 0; i < clampedDays; i++) {
                series.add(cutoff.plusDays(i).format(DAY_KEY));
            }
        }

        List<ActivityPointResponse> result = series.stream()
                .map(key -> new ActivityPointResponse(key, counts.getOrDefault(key, 0L)))
                .toList();
        return ResponseEntity.ok(result);
    }

    private static void mergeCounts(Map<String, Long> target, List<Object[]> rows) {
        for (Object[] row : rows) {
            String key = (String) row[0];
            long value = ((Number) row[1]).longValue();
            target.merge(key, value, Long::sum);
        }
    }

    private static final java.util.Set<String> BREAKDOWN_DIMENSIONS = java.util.Set.of("department", "role", "status");

    @GetMapping("/api/statistics/breakdown")
    public ResponseEntity<?> getStatisticsBreakdown(
            @RequestParam String dimension, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireStatsView(user);
        if (denial != null) {
            return denial;
        }
        if (!BREAKDOWN_DIMENSIONS.contains(dimension)) {
            return ResponseEntity.badRequest().body(Map.of("detail", "dimension must be one of: department, role, status"));
        }

        List<BreakdownItemResponse> result = switch (dimension) {
            case "department" -> userRepository.countGroupedByDepartment().stream()
                    .map(row -> new BreakdownItemResponse((String) row[0], ((Number) row[1]).longValue()))
                    .toList();
            case "role" -> userRepository.countGroupedByRole().stream()
                    .map(row -> new BreakdownItemResponse(((Role) row[0]).value(), ((Number) row[1]).longValue()))
                    .toList();
            default -> readStatusRepository.countGroupedByStatus().stream()
                    .map(row -> new BreakdownItemResponse((String) row[0], ((Number) row[1]).longValue()))
                    .toList();
        };
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/statistics/kpi")
    public ResponseEntity<?> getKpiCounts(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireStatsView(user);
        if (denial != null) {
            return denial;
        }
        long activeUsers = userRepository.countByActiveTrue();
        KpiResponse result = new KpiResponse(
                activeUsers, articleRepository.count(),
                mandatoryItemsInForce(), videoInstructionRepository.count());
        return ResponseEntity.ok(result);
    }

    /**
     * The overview's "active mandatory material": items, not reading rows --
     * an article for two departments is one piece of material with two
     * readings -- and only those in force (PO-40). Every row once counted,
     * including readings of archived, trashed and never-published items.
     */
    private long mandatoryItemsInForce() {
        List<RequiredReading> readings = CompleteResultGuard.enforce(
                requiredReadingRepository.findAll(CompleteResultGuard.sentinelPage()).getContent());
        Set<Long> inForce = mandatoryReach.inForceIds(readings);
        return readings.stream()
                .filter(reading -> inForce.contains(reading.getId()))
                .map(reading -> reading.getItemType() + ":" + reading.getItemId())
                .distinct()
                .count();
    }

    private static double roundHalfEven(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static int parsePercentage(String percentageLabel) {
        return Integer.parseInt(percentageLabel.replace("%", ""));
    }

    /**
     * Phase 3: records what leadership-backed scoping would return and serves
     * the legacy answer. Null-safe so the DB-free controller tests, which
     * construct this without a resolver, measure nothing rather than fail.
     */
    private List<User> shadowScope(String decision, User caller, List<User> candidates, List<User> legacyVisible) {
        return scopeResolver == null
                ? legacyVisible
                : scopeResolver.shadowCompare(decision, caller, candidates, legacyVisible);
    }

    private List<User> boundedActiveUsers() {
        // The null branch exists only for the legacy DB-free constructor used
        // by pure controller tests. Spring production wiring always supplies
        // the bounded Oracle query service.
        return userDirectoryQueryService == null
                ? userRepository.findByActiveTrue()
                : userDirectoryQueryService.listActiveUsersWithinLimit();
    }

    private ResponseEntity<Map<String, String>> requireStatsView(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.STATS_VIEW)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireManagerOrAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.MANAGER && user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

}
