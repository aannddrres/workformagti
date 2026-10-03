package ge.magti.portal.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single, merged permission catalog for the Java port.
 *
 * <p>The original app carried two disjoint RBAC catalogs that were never meant
 * to coexist (known bug #5, docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md): 8
 * dot-named permissions checked against users.permissions (a plain JSON
 * string list), and a separate
 * 13 colon-named permissions seeded into Role/Permission/RolePermission DB
 * tables that no dotted check ever matches --
 * including the historical "system:audit" value. Raw system audit is now
 * SYSTEM_ADMIN-only and therefore is not a grantable catalog entry.
 *
 * <p>The Java port keeps one dot-notation catalog. The remaining colon-only
 * permissions are deliberately NOT ported here -- per that same decision
 * they get added one at a time, only if and when a Java endpoint actually
 * needs one:
 * users:manage, content:editor, content:publisher, compliance:manage,
 * reports:view_global, reports:export_sensitive, communication:broadcast,
 * content:archive, reports:export_personal_data.
 * (reports:export and compliance:assign are not in that leftover list --
 * they already have dotted equivalents below.)
 */
public enum Permission {
    // ARTICLES_VIEW ("articles.view") was REMOVED here (audit SEC-06).
    //
    // It was in the catalog, rendered as a switch in the admin UI, validated
    // and persisted -- and enforced nowhere. Unlike users.manage and
    // compliance.assign, which were fixed by enforcing them, this one could
    // not be: OPERATOR's default permission set is EMPTY (see
    // DEFAULTS_BY_ROLE below), so making articles.view a real gate would
    // have locked every operator out of the entire knowledge base -- the
    // product's whole purpose. ArticleController's javadoc had already
    // recorded the gap and the reason it was not closed.
    //
    // A switch that cannot safely be made to work is worse than no switch:
    // it tells an administrator they have a control they do not have. Read
    // access to articles is governed by target-department visibility
    // (ArticleQueryService) and by role, which is where it belongs.
    //
    // Persisted rows may still carry the string "articles.view". That is
    // harmless -- User.hasPermission compares against this enum's values, so
    // a stale entry simply never matches anything. PUT
    // /api/users/{id}/permissions will now reject it as unknown, which is
    // correct: the frontend catalog no longer offers it.
    ARTICLES_EDIT("articles.edit"),
    ARTICLES_ARCHIVE("articles.archive"),
    VIDEOS_ARCHIVE("videos.archive"),
    CONTENT_MANAGE("content.manage"),
    // USERS_MANAGE ("users.manage") was REMOVED here too (audit SEC-06),
    // for a reason the audit did not surface and that only appeared when a
    // test tried to enforce it: PermissionChecker.hasPermission returns TRUE
    // unconditionally for SYSTEM_ADMIN. Combined with the fact that every
    // user-administration endpoint also requires the SYSTEM_ADMIN role,
    // users.manage was only ever evaluated for the one role that bypasses
    // the evaluation -- structurally incapable of affecting any decision,
    // no matter what the admin UI's switch said.
    //
    // Enforcing it therefore needed one of two changes that are bigger than
    // a bug fix, and both are the owner's call, not a 2am one:
    //   (a) drop the SYSTEM_ADMIN bypass, making permissions bind for
    //       admins too -- honest, but any admin row with an incomplete
    //       permission set silently loses abilities on deploy; or
    //   (b) let users.manage DELEGATE user administration to a non-admin,
    //       which is a new capability, not a fix.
    // Until one is chosen, the switch is not shipped. See the report and
    // PermissionChecker's javadoc.
    COMPLIANCE_ASSIGN("compliance.assign"),
    STATS_VIEW("stats.view"),
    REPORTS_EXPORT("reports.export");

    private static final Map<Role, Set<Permission>> DEFAULTS_BY_ROLE = Map.of(
            Role.OPERATOR, EnumSet.noneOf(Permission.class),
            Role.MANAGER, EnumSet.of(REPORTS_EXPORT),
            Role.CONTENT_ADMIN, EnumSet.of(
                    ARTICLES_EDIT, ARTICLES_ARCHIVE,
                    VIDEOS_ARCHIVE, CONTENT_MANAGE, COMPLIANCE_ASSIGN),
            Role.SYSTEM_ADMIN, EnumSet.of(
                    ARTICLES_EDIT, ARTICLES_ARCHIVE,
                    VIDEOS_ARCHIVE, CONTENT_MANAGE, COMPLIANCE_ASSIGN, STATS_VIEW, REPORTS_EXPORT));

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Permission fromValue(String value) {
        return Arrays.stream(values())
                .filter(permission -> permission.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown permission: " + value));
    }

    /**
     * Returns the default grant set for the canonical role. Direct article
     * publishing is included in {@code articles.edit}; raw system audit is a
     * SYSTEM_ADMIN role boundary and neither appears as an independent grant.
     *
     * <p>Unrelated to known bug #4: a separate
     * admin-facing "known permissions" whitelist on the manual
     * permission-edit endpoint lists only 7 of the 8 dotted constants,
     * omitting VIDEOS_ARCHIVE -- so today, editing a user's permissions by
     * hand can never grant it even though CONTENT_ADMIN/SYSTEM_ADMIN get it
     * by default above. Not fixed here (that whitelist hasn't been ported
     * yet); noted because whoever ports that endpoint should validate
     * against {@code Permission.values()} rather than hand-copying a
     * 7-item set a second time, which would remove the trap rather than
     * carry it forward.
     */
    public static Set<Permission> defaultsFor(Role role) {
        return DEFAULTS_BY_ROLE.get(role);
    }
}
