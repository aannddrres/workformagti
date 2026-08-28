package ge.magti.portal.security;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.compliance.ComplianceEligibilityService;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.web.AccessDiffComplianceResponse;
import ge.magti.portal.web.AccessDiffResponse;
import ge.magti.portal.web.AccessDiffRowResponse;
import ge.magti.portal.web.AccessDiffScopeResponse;
import ge.magti.portal.web.AccessDiffTotalsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds the read-only, per-user evidence required before Phase 4/5. */
@Service
public class AccessDiffService {

    private final UserRepository userRepository;
    private final LeadershipAssignmentRepository assignmentRepository;
    private final TeamRepository teamRepository;
    private final UserDirectoryQueryService userDirectoryQueryService;
    private final OrgDirectoryQueryService orgDirectoryQueryService;

    /** DB-free compatibility constructor used by the existing pure unit suite. */
    public AccessDiffService(
            UserRepository userRepository,
            LeadershipAssignmentRepository assignmentRepository,
            TeamRepository teamRepository) {
        this(userRepository, assignmentRepository, teamRepository, null, null);
    }

    public AccessDiffService(
            UserRepository userRepository,
            LeadershipAssignmentRepository assignmentRepository,
            TeamRepository teamRepository,
            UserDirectoryQueryService userDirectoryQueryService) {
        this(userRepository, assignmentRepository, teamRepository, userDirectoryQueryService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccessDiffService(
            UserRepository userRepository,
            LeadershipAssignmentRepository assignmentRepository,
            TeamRepository teamRepository,
            UserDirectoryQueryService userDirectoryQueryService,
            OrgDirectoryQueryService orgDirectoryQueryService) {
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.teamRepository = teamRepository;
        this.userDirectoryQueryService = userDirectoryQueryService;
        this.orgDirectoryQueryService = orgDirectoryQueryService;
    }

    @Transactional(readOnly = true)
    public AccessDiffResponse report() {
        List<User> allUsers = boundedDirectoryUsers().stream()
                .sorted(Comparator.comparing(User::getId))
                .toList();
        List<User> activeUsers = allUsers.stream().filter(User::isActive).toList();
        List<LeadershipAssignment> activeAssignments = activeAssignments();
        List<Team> teams = teams();

        long gains = 0;
        long losses = 0;
        long unchanged = 0;
        List<AccessDiffRowResponse> rows = new ArrayList<>();

        for (User user : allUsers) {
            boolean legacyCompliance = ComplianceCalculator.isEligible(user);
            boolean hasLeadership = activeAssignments.stream()
                    .anyMatch(assignment -> Objects.equals(assignment.getUserId(), user.getId()));
            boolean proposedCompliance = ComplianceEligibilityService.resolve(user, hasLeadership);

            int legacyScopeCount = legacyScopeCount(activeUsers, user);
            Scope proposedScope = ScopeResolver.resolveFromSnapshot(user, activeAssignments, teams);
            int proposedScopeCount = visibleCount(activeUsers, proposedScope);

            boolean hasLoss = (legacyCompliance && !proposedCompliance)
                    || proposedScopeCount < legacyScopeCount;
            boolean hasGain = (!legacyCompliance && proposedCompliance)
                    || proposedScopeCount > legacyScopeCount;

            // A mixed row is loss-classified: the totals are mutually
            // exclusive and a cutover gate must never hide that somebody
            // loses one kind of access merely because they gain another.
            if (hasLoss) {
                losses++;
            } else if (hasGain) {
                gains++;
            } else {
                unchanged++;
            }

            if (legacyCompliance != proposedCompliance || legacyScopeCount != proposedScopeCount) {
                rows.add(new AccessDiffRowResponse(
                        user.getId(), user.getName(), user.getRole().value(),
                        new AccessDiffComplianceResponse(legacyCompliance, proposedCompliance),
                        new AccessDiffScopeResponse(legacyScopeCount, proposedScopeCount)));
            }
        }

        return new AccessDiffResponse(
                TbilisiTime.now(),
                new AccessDiffTotalsResponse(allUsers.size(), gains, losses, unchanged),
                List.copyOf(rows));
    }

    private List<User> boundedDirectoryUsers() {
        // Production is always database-bounded. The fallback exists only for
        // the older repository-mock constructor used by DB-free pure tests.
        return userDirectoryQueryService == null
                ? userRepository.findAll()
                : userDirectoryQueryService.listUsersWithinLimit();
    }

    private List<LeadershipAssignment> activeAssignments() {
        // Production uses the bounded org module; the fallback is only for
        // the older repository-mock constructors used by pure tests.
        return orgDirectoryQueryService == null
                ? assignmentRepository.findByActiveTrue()
                : orgDirectoryQueryService.listActiveAssignmentsWithinLimit();
    }

    private List<Team> teams() {
        // See activeAssignments(): no Spring production path uses findAll().
        return orgDirectoryQueryService == null
                ? teamRepository.findAll()
                : orgDirectoryQueryService.listTeamsWithinLimit();
    }

    private static int legacyScopeCount(List<User> activeUsers, User user) {
        if (!user.isActive()) {
            return 0;
        }
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            return activeUsers.size();
        }
        if (user.getRole() == Role.MANAGER) {
            return ManagerScope.visibleActiveUsers(activeUsers, user).size();
        }
        return 0;
    }

    private static int visibleCount(List<User> activeUsers, Scope scope) {
        if (scope.unscoped()) {
            return activeUsers.size();
        }
        if (scope.readsNobody()) {
            return 0;
        }
        return (int) activeUsers.stream()
                .filter(candidate -> scope.includesTeam(candidate.getTeamId()))
                .count();
    }
}
