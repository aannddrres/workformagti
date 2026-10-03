package ge.magti.portal.stats;

import java.util.List;

/**
 * The department whitelist and its bucket match -- a third, separate department mechanism
 * from {@link ge.magti.portal.util.DepartmentMatcher} (eligibility/
 * visibility) and {@link ge.magti.portal.domain.Article}'s dual targeting
 * (finding #19): this one maps any department prefix down to one of
 * exactly 3 broad organizational buckets, purely for dashboard grouping.
 *
 * <p>There is no fallback loop over the whitelist after the three explicit
 * checks: it would be provably unreachable, since every string it could
 * match already matches one of the three checks, two of which test
 * {@code startsWith} for that exact whitelist entry already.
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
