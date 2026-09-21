package ge.magti.portal.security;

import ge.magti.portal.domain.Role;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the directory's authorities into the one portal role a user holds.
 *
 * <p>The directory decides roles on every sign-in (owner decision,
 * 2026-09-21), so this is the only place a portal role is derived from
 * outside the portal. The mapping is configuration
 * ({@code OAUTH_ROLE_MAP}) because the authority names are created on IT's
 * side and were not yet fixed when this was written.
 *
 * <p>A user holds one role, so two mapped authorities need a winner: system
 * admin, then content admin, then manager, then operator. A user with more
 * than one is almost certainly a mistake in the directory rather than a
 * design, so the caller is told and logs it. No mapped authority at all means
 * operator -- the least a signed-in employee can be.
 */
public final class DirectoryRoleMapper {

    static final List<Role> PRECEDENCE = List.of(Role.SYSTEM_ADMIN, Role.CONTENT_ADMIN, Role.MANAGER, Role.OPERATOR);

    private final Map<String, Role> roleByAuthority;

    /**
     * @param specification comma-separated {@code AUTHORITY=role}, role being
     *                      the portal's wire value ({@code admin},
     *                      {@code content_admin}, {@code manager}, {@code operator})
     * @throws IllegalArgumentException on anything unparseable, so a typo in
     *                                  the deployment fails the boot instead
     *                                  of silently demoting everyone to operator
     */
    DirectoryRoleMapper(String specification) {
        Map<String, Role> parsed = new LinkedHashMap<>();
        if (specification != null) {
            for (String entry : specification.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0 || separator == trimmed.length() - 1) {
                    throw new IllegalArgumentException("OAUTH_ROLE_MAP entry is not AUTHORITY=role: " + trimmed);
                }
                String authority = trimmed.substring(0, separator).trim();
                String role = trimmed.substring(separator + 1).trim();
                parsed.put(authority, Role.fromValue(role));
            }
        }
        this.roleByAuthority = Map.copyOf(parsed);
    }

    /** Parses the map and throws on anything wrong -- for the boot-time guard. */
    public static void validate(String specification) {
        new DirectoryRoleMapper(specification);
    }

    /** Every portal role the authorities map to, however many. */
    Set<Role> mappedRoles(Collection<String> authorities) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String authority : authorities) {
            Role role = roleByAuthority.get(authority);
            if (role != null) {
                roles.add(role);
            }
        }
        return roles;
    }

    Role roleFor(Collection<String> authorities) {
        Set<Role> roles = mappedRoles(authorities);
        for (Role candidate : PRECEDENCE) {
            if (roles.contains(candidate)) {
                return candidate;
            }
        }
        return Role.OPERATOR;
    }
}
