package ge.magti.portal.compliance;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ports the DB-free half of the compliance-percentage calculation. Python
 * actually carries <b>two</b> implementations of this same formula --
 * routers/stats.py's {@code _reading_progress} (used by the live dashboard,
 * team stats, and PDF export) and compliance_utils.py's
 * {@code get_compliance_data_tuple} (used only by compliance_alerts.py, the
 * standalone daily cron -- CLAUDE.md notes it runs as "a separate process,
 * not imported by main.py"). They're kept in agreement by an explicit
 * regression test (tests/test_compliance.py), not by sharing code, because
 * Python's cron script avoids importing a FastAPI router module.
 *
 * <p><b>That constraint doesn't exist in Spring</b>: a {@code @Scheduled}
 * job and a {@code @RestController} can call the same {@code @Service}
 * bean in-process. This class is deliberately the Java port's only
 * implementation -- there's no reason to carry the Python split forward,
 * and doing so would just recreate a two-copies-to-keep-in-sync risk this
 * side doesn't need.
 *
 * <p><b>Rounding is the one place this needs to be more careful than a
 * literal line-for-line port</b>: Python's {@code round()} uses
 * round-half-to-even (e.g. {@code round(12.5) == 12}, {@code round(37.5)
 * == 38}); Java's {@code Math.round()} always rounds half up
 * ({@code Math.round(12.5) == 13}). A compliance percentage landing
 * exactly on a .5 tie (e.g. 1 of 8 required = 12.5%) would silently differ
 * between the two languages if {@code Math.round} were used here. This
 * class uses {@link Math#rint(double)} instead, which matches Python's
 * round-half-to-even -- verified with both a round-down-to-even and a
 * round-up-to-even case in {@code ComplianceCalculatorTest}, not just one.
 */
public final class ComplianceCalculator {

    /**
     * Mirrors compliance_utils.py's MANAGEMENT_ROLES (compliance_utils.py:18)
     * -- excluded from compliance eligibility; only operators are the
     * intended audience.
     */
    public static final Set<Role> MANAGEMENT_ROLES =
            EnumSet.of(Role.SYSTEM_ADMIN, Role.CONTENT_ADMIN, Role.MANAGER);

    /** Mirrors compliance_utils.py's CRITICAL_THRESHOLD (compliance_utils.py:19). */
    public static final int CRITICAL_THRESHOLD = 30;

    private ComplianceCalculator() {
    }

    /** The one eligibility rule: active, and not a management role. */
    public static boolean isEligible(User user) {
        return user.isActive() && !MANAGEMENT_ROLES.contains(user.getRole());
    }

    /**
     * Computes (required, read, percentage) for one user from
     * pre-aggregated maps -- no per-user query, mirroring
     * routers/stats.py:227-251 exactly. Applicable readings are those
     * targeting "All" plus those targeting the user's own department,
     * matched prefix-aware via {@link DepartmentMatcher} -- the same rule
     * {@link DepartmentMatcher#matches} uses for visibility, so this can't
     * disagree with what a user's own reading list shows them.
     *
     * @param allRequired            count of readings targeting "All" -- passed in rather
     *                               than looked up here, matching Python's call site
     * @param requiredCountsByDept   {@code target_department -> count} across all required readings
     * @param readCountsByUserDept   {@code (user_id, target_department) -> count} of this user's read statuses
     */
    public static ReadingProgress computeProgress(
            User user,
            int allRequired,
            Map<String, Integer> requiredCountsByDept,
            Map<ReadCountKey, Integer> readCountsByUserDept) {

        String department = user.getDepartment();
        int requiredCount;
        int readCount;

        if (department == null || "All".equals(department)) {
            requiredCount = allRequired;
            readCount = readCountsByUserDept.getOrDefault(new ReadCountKey(user.getId(), "All"), 0);
        } else {
            // LinkedHashSet, not Set.of(...): the department and its own
            // prefix are frequently identical (any department with no "—
            // ჯგუფი" suffix), and Set.of throws on duplicate elements where
            // Python's set literal would just silently deduplicate.
            Set<String> deptKeys = new LinkedHashSet<>();
            deptKeys.add(department);
            deptKeys.add(DepartmentMatcher.splitGroup(department).prefix());

            int deptRequiredSum = 0;
            int deptReadSum = 0;
            for (String key : deptKeys) {
                deptRequiredSum += requiredCountsByDept.getOrDefault(key, 0);
                deptReadSum += readCountsByUserDept.getOrDefault(new ReadCountKey(user.getId(), key), 0);
            }
            requiredCount = allRequired + deptRequiredSum;
            readCount = readCountsByUserDept.getOrDefault(new ReadCountKey(user.getId(), "All"), 0) + deptReadSum;
        }

        if (requiredCount == 0) {
            return new ReadingProgress(0, 0, 0);
        }

        int percentage = (int) Math.rint((double) readCount / requiredCount * 100);
        return new ReadingProgress(requiredCount, readCount, percentage);
    }
}
