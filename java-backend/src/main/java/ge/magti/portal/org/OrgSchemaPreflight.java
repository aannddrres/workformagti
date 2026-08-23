package ge.magti.portal.org;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers one question: would V37 apply, or would it fail halfway?
 *
 * <p>V36 deliberately left {@code teams} loose -- {@code department_id}
 * nullable, no uniqueness on the name at all after the global
 * {@code uq_teams_name} was dropped -- because the backfill is what makes the
 * tighter constraints satisfiable, and shipping both in one deployment is the
 * failure the two-release split exists to prevent. V37 is the contract half.
 *
 * <p>An {@code ALTER TABLE ... NOT NULL} against a column with nulls, or a
 * {@code CREATE UNIQUE INDEX} against duplicate rows, does not warn: it raises
 * ORA-02296 or ORA-01452 and leaves the deployment part-applied, in a change
 * window, on the release whose whole purpose was to be uneventful. The plan
 * (ORG_ACCESS_ARCHITECTURE_PLAN_KA.md §7.2, §8) therefore requires a blocking
 * preflight before V37 runs. This is it, and it is readable through
 * {@code GET /api/admin/org-schema-preflight} rather than only from a DBA
 * session, because the person who has to decide is the one running the
 * backfill.
 *
 * <p><b>What V37 will actually add is narrower than the plan text says.</b>
 * The plan lists the one-scope CHECK and the two function-based primary-leader
 * unique indexes among V37's contents; V36 already created all three
 * ({@code ck_leadership_one_scope}, {@code uq_leadership_primary_dept},
 * {@code uq_leadership_primary_team}), as it did the departments external-id
 * uniqueness. Writing them again in V37 would fail with ORA-00955. What is
 * genuinely left is three constraints on {@code teams}, and those are the
 * three blocking checks below.
 *
 * <p>The primary-leader collisions are still counted, as verification rather
 * than as a gate. Flyway's history table records that V36 applied; it cannot
 * tell whether V36's objects are still there. A schema restored from an older
 * dump, or one a DBA has hand-edited, passes every version check and can still
 * be missing the index that makes those collisions impossible -- and the
 * moment it is missing, this preflight is the only thing that would notice.
 */
@Service
public class OrgSchemaPreflight {

    private final JdbcTemplate jdbcTemplate;

    public OrgSchemaPreflight(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * One thing V37 will assert and cannot.
     *
     * @param constraint the object V37 creates, so the report names what would
     *                   fail rather than describing it
     * @param blocking   false for a finding that V36 already prevents; it is
     *                   still reported, because "cannot happen" is a claim
     *                   about a schema, and this checks the schema
     */
    public record Violation(
            String constraint, String detail, long rows, List<String> samples, boolean blocking) {
    }

    public record Report(List<Violation> violations) {

        /** Nothing at all found -- neither blocking nor verification. */
        public boolean clean() {
            return violations.isEmpty();
        }

        /** The gate the plan asks for: V37 may run only when this is false. */
        public boolean blocksV37() {
            return violations.stream().anyMatch(Violation::blocking);
        }
    }

    private static final int SAMPLE_LIMIT = 10;

    @Transactional(readOnly = true)
    public Report run() {
        List<Violation> violations = new ArrayList<>();

        collect(violations, "teams.department_id NOT NULL", true,
                "group(s) have no department, so the column cannot be made NOT NULL. "
                        + "Every group must belong to exactly one department before V37.",
                """
                SELECT id || ' (' || name || ')' FROM teams WHERE department_id IS NULL ORDER BY id
                """);

        collect(violations, "uq_teams_department_name", true,
                "department/name pair(s) are used by more than one group. V36 dropped the global "
                        + "uq_teams_name and V37 replaces it per department; a duplicate blocks that.",
                """
                SELECT department_id || ' / ' || name || ' x' || COUNT(*)
                  FROM teams
                 WHERE department_id IS NOT NULL
                 GROUP BY department_id, name
                HAVING COUNT(*) > 1
                 ORDER BY COUNT(*) DESC
                """);

        collect(violations, "uq_teams_ad_external_id", true,
                "directory identifier(s) are claimed by more than one group. Two groups sharing one "
                        + "AD id makes the sync ambiguous about which row it is updating.",
                """
                SELECT ad_external_id || ' x' || COUNT(*)
                  FROM teams
                 WHERE ad_external_id IS NOT NULL
                 GROUP BY ad_external_id
                HAVING COUNT(*) > 1
                 ORDER BY COUNT(*) DESC
                """);

        collect(violations, "uq_leadership_primary_dept", false,
                "department(s) have more than one active PRIMARY leader. V36's unique index makes this "
                        + "impossible; finding one means that index is not on this schema.",
                """
                SELECT 'department ' || department_id || ' x' || COUNT(*)
                  FROM leadership_assignments
                 WHERE is_active = 1 AND assignment_type = 'PRIMARY' AND department_id IS NOT NULL
                 GROUP BY department_id
                HAVING COUNT(*) > 1
                 ORDER BY COUNT(*) DESC
                """);

        collect(violations, "uq_leadership_primary_team", false,
                "group(s) have more than one active PRIMARY leader. V36's unique index makes this "
                        + "impossible; finding one means that index is not on this schema.",
                """
                SELECT 'group ' || team_id || ' x' || COUNT(*)
                  FROM leadership_assignments
                 WHERE is_active = 1 AND assignment_type = 'PRIMARY' AND team_id IS NOT NULL
                 GROUP BY team_id
                HAVING COUNT(*) > 1
                 ORDER BY COUNT(*) DESC
                """);

        return new Report(List.copyOf(violations));
    }

    /**
     * Each query returns one already-rendered line per offending row or group,
     * so the report can be read without a second lookup: an operator seeing
     * "5 / ტექნიკური x2" knows which pair to go and fix.
     */
    private void collect(
            List<Violation> violations, String constraint, boolean blocking, String detail, String sql) {
        List<String> found = jdbcTemplate.queryForList(sql, String.class);
        if (found.isEmpty()) {
            return;
        }
        violations.add(new Violation(
                constraint,
                found.size() + " " + detail,
                found.size(),
                found.size() > SAMPLE_LIMIT ? List.copyOf(found.subList(0, SAMPLE_LIMIT)) : List.copyOf(found),
                blocking));
    }
}
