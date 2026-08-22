package ge.magti.portal.security;

import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The one answer to "whose data may this caller read" (plan §5.5).
 *
 * <p>Replaces {@link ManagerScope}, which answered it from
 * {@code users.department} -- a free-text string an administrator types. Every
 * scope defect in this codebase came from that: SEC-13 (exact match silently
 * under-including a parent manager to zero rows), SEC-02 (exports with no
 * scope at all), bug #312 (the department taken from the request path).
 *
 * <h2>Fail-closed, and what that costs</h2>
 *
 * Only {@link Role#SYSTEM_ADMIN} is unscoped. Everyone else gets exactly the
 * teams their <b>active leadership assignments</b> cover, and no assignment
 * means no scope -- not "their own department", which is what the interim
 * Phase 0 rule still does. A permission does not widen this: holding
 * {@code reports.export} says the caller may run an export, never whose data
 * goes in it (plan §8).
 *
 * <p>The cost is real and worth stating: at cutover, a manager whose backfill
 * candidate was never confirmed reads nobody. That is why Phase 2's
 * reconciliation report is a gate on Phase 4 rather than a nice-to-have --
 * fail-closed only behaves well if somebody has looked at the list of people
 * it will close on.
 *
 * <h2>Shadow mode</h2>
 *
 * Nothing enforces this yet. {@link #shadowCompare} exists so the call sites
 * that still run {@link ManagerScope} can record what would have changed,
 * which is what Phase 4 needs to cut over on evidence instead of on
 * confidence.
 */
@Service
public class ScopeResolver {

    private final LeadershipAssignmentRepository leadershipAssignmentRepository;
    private final TeamRepository teamRepository;
    private final PolicyShadowRecorder shadowRecorder;

    public ScopeResolver(
            LeadershipAssignmentRepository leadershipAssignmentRepository,
            TeamRepository teamRepository,
            PolicyShadowRecorder shadowRecorder) {
        this.leadershipAssignmentRepository = leadershipAssignmentRepository;
        this.teamRepository = teamRepository;
        this.shadowRecorder = shadowRecorder;
    }

    /** The caller's scope over other employees' personal and statistical data. */
    public Scope resolve(User caller) {
        if (caller == null || !caller.isActive()) {
            return Scope.none();
        }
        if (caller.getRole() == Role.SYSTEM_ADMIN) {
            return Scope.all();
        }

        List<LeadershipAssignment> assignments =
                leadershipAssignmentRepository.findByUserIdAndActiveTrue(caller.getId());
        if (assignments.isEmpty()) {
            return Scope.none();
        }

        boolean hasDepartmentAssignment = assignments.stream()
                .anyMatch(assignment -> assignment.getDepartmentId() != null);
        List<Team> teams = hasDepartmentAssignment ? teamRepository.findAll() : List.of();
        return resolveFromSnapshot(caller, assignments, teams);
    }

    /**
     * Resolves the canonical policy from a read-only snapshot.
     *
     * <p>The access-diff report evaluates every stored user. Supplying one
     * snapshot avoids two repository queries per user while keeping this
     * method's answer byte-for-byte aligned with {@link #resolve(User)}.
     */
    public static Scope resolveFromSnapshot(
            User caller, List<LeadershipAssignment> activeAssignments, List<Team> teams) {
        if (caller == null || !caller.isActive()) {
            return Scope.none();
        }
        if (caller.getRole() == Role.SYSTEM_ADMIN) {
            return Scope.all();
        }

        Set<Long> teamIds = new LinkedHashSet<>();
        Set<Long> departmentIds = new LinkedHashSet<>();
        for (LeadershipAssignment assignment : activeAssignments) {
            if (!assignment.isActive() || !Objects.equals(assignment.getUserId(), caller.getId())) {
                continue;
            }
            if (assignment.getTeamId() != null) {
                teamIds.add(assignment.getTeamId());
            } else if (assignment.getDepartmentId() != null) {
                departmentIds.add(assignment.getDepartmentId());
            }
        }

        // A department assignment is expanded into its groups here, once, so
        // that every reader downstream only has to ask includesTeam(). Doing it
        // at each call site is how five places came to answer the same question
        // five ways (SEC-13).
        if (!departmentIds.isEmpty()) {
            for (Team team : teams) {
                if (team.getDepartmentId() != null && departmentIds.contains(team.getDepartmentId())) {
                    teamIds.add(team.getId());
                }
            }
        }
        return Scope.of(teamIds, departmentIds);
    }

    /** The users, out of {@code candidates}, whose data {@code caller} may read. */
    public List<User> visibleUsers(List<User> candidates, User caller) {
        Scope scope = resolve(caller);
        if (scope.unscoped()) {
            return List.copyOf(candidates);
        }
        if (scope.readsNobody()) {
            return List.of();
        }
        return candidates.stream().filter(candidate -> scope.includesTeam(candidate.getTeamId())).toList();
    }

    /**
     * Records what this resolver would have returned, and returns the legacy
     * answer unchanged.
     *
     * <p>Returning {@code legacyVisible} rather than {@code void} is the point:
     * a call site written as {@code return resolver.shadowCompare(...)} cannot
     * accidentally start enforcing the new rule, and cannot forget to record
     * either. The two are the same expression.
     *
     * <p>Compares the id sets, not the lists -- ordering differences between
     * the two rules are not access differences and would drown the real signal.
     */
    public List<User> shadowCompare(String decision, User caller, List<User> candidates, List<User> legacyVisible) {
        try {
            Set<Long> legacyIds = idsOf(legacyVisible);
            Set<Long> proposedIds = idsOf(visibleUsers(candidates, caller));
            shadowRecorder.record(decision, caller == null ? null : caller.getId(), legacyIds, proposedIds);
        } catch (RuntimeException e) {
            // A measurement must never take down the request it measures.
            // Phase 4 makes this path decide things; until then a failure here
            // costs one data point.
            shadowRecorder.record(decision + ".error", caller == null ? null : caller.getId(), "ok", e.getClass().getSimpleName());
        }
        return legacyVisible;
    }

    private static Set<Long> idsOf(List<User> users) {
        Set<Long> ids = new LinkedHashSet<>();
        for (User user : users) {
            ids.add(user.getId());
        }
        return ids;
    }
}
