package ge.magti.portal.compliance;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The DB-free half of the compliance-percentage calculation.
 *
 * <p>A {@code @Scheduled} job and a {@code @RestController} can call the
 * same {@code @Service} bean in-process, so this class is deliberately the
 * only implementation of the formula -- a second copy would just create a
 * two-copies-to-keep-in-sync risk.
 *
 * <p><b>Rounding is round-half-to-even</b> (12.5 -> 12, 37.5 -> 38), via
 * {@link Math#rint(double)}. Java's {@code Math.round()} always rounds half
 * up ({@code Math.round(12.5) == 13}), so a compliance percentage landing
 * exactly on a .5 tie (e.g. 1 of 8 required = 12.5%) would silently differ
 * if it were used here -- verified with both a round-down-to-even and a
 * round-up-to-even case in {@code ComplianceCalculatorTest}, not just one.
 */
public final class ComplianceCalculator {

    /**
     * Management roles -- excluded from compliance eligibility; only
     * operators are the intended audience.
     */
    public static final Set<Role> MANAGEMENT_ROLES =
            EnumSet.of(Role.SYSTEM_ADMIN, Role.CONTENT_ADMIN, Role.MANAGER);

    public static final int CRITICAL_THRESHOLD = 30;

    private ComplianceCalculator() {
    }

    /** The one eligibility rule: active, and not a management role. */
    public static boolean isEligible(User user) {
        return user.isActive() && !MANAGEMENT_ROLES.contains(user.getRole());
    }

    /**
     * Computes (required, read, percentage) for one user from
     * pre-aggregated maps -- no per-user query. Applicable readings are those
     * targeting "All" plus those targeting the user's own department,
     * matched prefix-aware via {@link DepartmentMatcher} -- the same rule
     * {@link DepartmentMatcher#matches} uses for visibility, so this can't
     * disagree with what a user's own reading list shows them.
     *
     * @param allRequired            count of readings targeting "All" -- passed in rather
     *                               than looked up here
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
            // ჯგუფი" suffix), and Set.of throws on duplicate elements.
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
