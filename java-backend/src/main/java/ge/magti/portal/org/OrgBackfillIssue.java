package ge.magti.portal.org;

/**
 * One row of the reconciliation report: something the backfill refused to
 * decide on its own.
 *
 * <p>The plan makes this fail-closed (§5.3): only an unambiguous group match
 * may become an assignment automatically. Everything else lands here for a
 * system admin to resolve before the Phase 4 cutover, because the cost of
 * guessing wrong is asymmetric -- an operator with no group sees an empty
 * screen and says so, while a manager silently pointed at the wrong group
 * reads a team that is not theirs and nothing complains.
 *
 * @param kind    what could not be decided
 * @param userId  the row it concerns
 * @param subject the free-text department string, or the group the collision is on
 * @param detail  what a human needs in order to resolve it
 */
public record OrgBackfillIssue(Kind kind, Long userId, String subject, String detail) {

    public enum Kind {
        /** {@code users.department} is null or blank -- no department, no group, no scope. */
        NO_DEPARTMENT,

        /** The literal wildcard "All". Means "everyone" for content; means nothing as an org unit. */
        WILDCARD_DEPARTMENT,

        /** The prefix matches none of the three official departments. */
        UNMAPPED_DEPARTMENT,

        /**
         * A MANAGER whose department names a department but no group.
         *
         * <p>Not auto-assigned as a department head: that role exists in the
         * data model but is explicitly not part of the first rollout (plan §2),
         * so inventing one here would hand somebody every group in their
         * department on the strength of a missing suffix.
         */
        DEPARTMENT_ONLY_MANAGER,

        /** A MANAGER whose department cannot be resolved to a group at all. */
        UNRESOLVED_MANAGER,

        /**
         * Two or more MANAGERs resolve to the same group.
         *
         * <p>V36's function-based unique index would reject the second write,
         * so this is reported and NEITHER is assigned -- picking one by row
         * order would make the outcome depend on the order the rows came back.
         */
        DUPLICATE_PRIMARY
    }
}
