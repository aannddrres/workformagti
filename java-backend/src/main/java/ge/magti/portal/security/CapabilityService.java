package ge.magti.portal.security;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The central "what may this caller do" check (plan §5.4), computed from the
 * role's defaults plus this person's explicit overrides.
 *
 * <h2>Why not just read users.permissions</h2>
 *
 * The legacy flat JSON list cannot say whether a
 * permission is there because the role grants it or because an administrator
 * granted it to this person. Without that distinction a role change has to
 * either wipe explicit grants or keep grants the new role never had. Both are
 * wrong, and the first is what {@code bulkReassignRoles} does today: moving
 * somebody operator to manager silently deletes content permissions an
 * administrator deliberately gave them.
 *
 * <p>So the answer is composed, not stored: role default, then
 * {@code ALLOW}/{@code DENY} on top. The absence of an override row means
 * inherit, which makes "revert to the default" a delete that cannot drift from
 * {@link Permission#defaultsFor}.
 *
 * <h2>SYSTEM_ADMIN still bypasses, and that is still load-bearing</h2>
 *
 * Kept from {@link PermissionChecker} deliberately -- plan §2 says a system
 * admin retains unconditional functional access. The consequence documented
 * there applies here unchanged: a permission only SYSTEM_ADMIN can hold can
 * never be refused, so it can never be a real gate. That is why AD-owned org
 * mutations are fail-closed by having no endpoint at all rather than by a
 * permission check, which this method would wave through.
 *
 * <h2>Cutover and retained diagnostics</h2>
 *
 * Phase 6 routes production permission checks through this service.
 * {@link #shadowCompare} remains available for rollout diagnostics and for
 * comparing legacy decisions while the compatibility column still exists.
 */
@Service
public class CapabilityService {

    private final UserPermissionOverrideRepository overrideRepository;
    private final PolicyShadowRecorder shadowRecorder;

    public CapabilityService(
            UserPermissionOverrideRepository overrideRepository, PolicyShadowRecorder shadowRecorder) {
        this.overrideRepository = overrideRepository;
        this.shadowRecorder = shadowRecorder;
    }

    public boolean hasCapability(User user, Permission permission) {
        if (user == null) {
            return false;
        }
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            return true;
        }
        return resolve(user, permission, overrideRepository.findByUserId(user.getId()));
    }

    /**
     * Every permission this user actually holds, resolved the same way a gate
     * resolves one.
     *
     * <p>Exists because {@code users.permissions} stopped deciding anything at
     * the Phase 6 cutover but is still what {@code /api/users/me} and the
     * admin user list ship to the frontend. A list that no longer matches the
     * rules in force is worse than no list: it tells someone they hold a
     * permission that has been denied them, or hides one they were granted.
     *
     * <p>One query rather than one per permission -- this is on the SPA's
     * bootstrap path.
     */
    public Set<Permission> effectivePermissions(User user) {
        if (user == null) {
            return Set.of();
        }
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            // Truthful rather than tidy: hasCapability short-circuits to true
            // for this role, so anything less would understate it.
            return EnumSet.allOf(Permission.class);
        }
        return effectivePermissions(user, overrideRepository.findByUserId(user.getId()));
    }

    /**
     * The same set with the overrides already in hand, for callers rendering a
     * page of users that batch-loaded them -- {@code GET /api/users} would
     * otherwise issue one query per row.
     */
    public static Set<Permission> effectivePermissions(User user, List<UserPermissionOverride> overrides) {
        if (user == null) {
            return Set.of();
        }
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            return EnumSet.allOf(Permission.class);
        }
        EnumSet<Permission> effective = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (resolve(user, permission, overrides)) {
                effective.add(permission);
            }
        }
        return effective;
    }

    /**
     * The rule itself, with the overrides passed in -- DB-free, so the
     * precedence between a role default and an explicit decision is unit
     * testable without a database standing in the way of reading it.
     */
    static boolean resolve(User user, Permission permission, List<UserPermissionOverride> overrides) {
        for (UserPermissionOverride override : overrides) {
            if (!permission.value().equals(override.getPermission())) {
                continue;
            }
            // An explicit decision beats the role in both directions. DENY
            // matters most: it is the only way to take a default away from
            // somebody without changing their role, and a model where the role
            // always wins would make it impossible.
            return override.getState() == UserPermissionOverride.State.ALLOW;
        }
        java.util.Set<Permission> defaults = Permission.defaultsFor(user.getRole());
        return defaults != null && defaults.contains(permission);
    }

    /**
     * Records what this service would answer and returns the legacy answer
     * unchanged. See {@link ScopeResolver#decide} for why this returns
     * the legacy value rather than {@code void}.
     */
    public boolean shadowCompare(String decision, User user, Permission permission, boolean legacy) {
        try {
            shadowRecorder.record(
                    decision + "." + permission.value(),
                    user == null ? null : user.getId(),
                    legacy,
                    hasCapability(user, permission));
        } catch (RuntimeException e) {
            shadowRecorder.record(decision + ".error", user == null ? null : user.getId(),
                    "ok", e.getClass().getSimpleName());
        }
        return legacy;
    }
}
