package ge.magti.portal.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whose data a caller may read -- the answer {@link ScopeResolver} produces.
 *
 * <p>Deliberately not a department string. Every scope bug this codebase has
 * had came from answering this question with free text: SEC-13 (exact match
 * under-including a parent manager to zero rows), SEC-02 (no scope at all on
 * exports), bug #312 (caller-controlled department in the path). A set of team
 * ids cannot be matched wrongly, cannot be passed in by the caller, and cannot
 * mean "everyone" because somebody's row happened to say "All".
 *
 * <p>{@link #unscoped} is a separate flag rather than "the set of every team",
 * because those two differ in the case that matters: a caller with no
 * assignments must read nobody, and an empty set that also meant "no
 * restriction" is precisely the fail-open this model exists to prevent. Only
 * SYSTEM_ADMIN is ever unscoped.
 *
 * <p>Department assignments are expanded into their teams at resolve time, so
 * a reader only ever has to check {@link #includesTeam}. {@link #departmentIds}
 * is kept alongside for the "why do you have this access" explanation the
 * system-admin UI owes an administrator, not for access decisions.
 */
public record Scope(boolean unscoped, Set<Long> teamIds, Set<Long> departmentIds) {

    public Scope {
        teamIds = teamIds == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(teamIds));
        departmentIds = departmentIds == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(departmentIds));
    }

    /**
     * SYSTEM_ADMIN only. Named {@code all()} rather than {@code unscoped()}
     * because the record component of that name already owns the accessor --
     * and {@code Scope.all()} reads better at a call site than the alternative
     * anyway.
     */
    public static Scope all() {
        return new Scope(true, Set.of(), Set.of());
    }

    /** No active leadership assignment: reads nobody. The default, and the safe one. */
    public static Scope none() {
        return new Scope(false, Set.of(), Set.of());
    }

    public static Scope of(Set<Long> teamIds, Set<Long> departmentIds) {
        return new Scope(false, teamIds, departmentIds);
    }

    /** True when this caller may read a person in {@code teamId}. */
    public boolean includesTeam(Long teamId) {
        if (unscoped) {
            return true;
        }
        // A null team is nobody's team. Not "everyone's" -- an unplaced user is
        // exactly the row whose scope is least well defined, so it fails closed.
        return teamId != null && teamIds.contains(teamId);
    }

    /** True when this caller may read nobody at all. Never true for {@link #unscoped()}. */
    public boolean readsNobody() {
        return !unscoped && teamIds.isEmpty();
    }
}
