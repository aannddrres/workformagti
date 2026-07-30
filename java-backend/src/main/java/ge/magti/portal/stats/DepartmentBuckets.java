package ge.magti.portal.stats;

import java.util.List;

/**
 * Mirrors routers/stats.py's DEPARTMENT_WHITELIST/_match_department_bucket
 * (routers/stats.py:537-557) -- a third, separate department mechanism
 * from {@link ge.magti.portal.util.DepartmentMatcher} (eligibility/
 * visibility) and {@link ge.magti.portal.domain.Article}'s dual targeting
 * (finding #19): this one maps any department prefix down to one of
 * exactly 3 broad organizational buckets, purely for dashboard grouping.
 *
 * <p>Python's function has a trailing loop after the three explicit
 * checks ({@code for wl in DEPARTMENT_WHITELIST: if raw.startswith(wl):
 * return wl}) that is provably unreachable: every string it could match
 * already matches one of the three {@code if}s above, since two of those
 * check {@code startswith(wl)} for that exact whitelist entry already.
 * Not ported -- there's no reachable behavior to preserve.
 */
public final class DepartmentBuckets {

    public static final List<String> WHITELIST = List.of("ტექნიკური", "საინფორმაციო", "ოფისი");

    private DepartmentBuckets() {
    }

    /** Returns one of {@link #WHITELIST}, or {@code null} if unrecognized. */
    public static String match(String prefix) {
        String raw = prefix == null ? "" : prefix.strip();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.startsWith("საინფორმაციო") || raw.startsWith("საინფო")) {
            return "საინფორმაციო";
        }
        if (raw.startsWith("ტექნიკური") || raw.startsWith("ტექნიკურ")) {
            return "ტექნიკური";
        }
        if (raw.startsWith("ოფისი")) {
            return "ოფისი";
        }
        return null;
    }
}
