package ge.magti.portal.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one shared rule
 * for matching a user's free-text {@code department} string (e.g.
 * "ტექნიკური — ჯგუფი 03") against a target department a
 * piece of content or a required reading was assigned to. Ported now,
 * ahead of Compliance/Stats, since every domain from here on depends on
 * this one definition -- Content's own visibility checks, Compliance
 * eligibility, and Stats department rollups all need it.
 *
 * <p>{@code MANAGEMENT_ROLES}/{@code CRITICAL_THRESHOLD}
 * and the DB-querying compliance-percentage
 * functions are deliberately NOT part of this
 * class -- those belong to the Compliance/Stats domain step, not the
 * department-matching rule itself.
 *
 * <p>The literal Georgian keyword and dash characters below rely on this
 * source file being read as UTF-8. Verified, not assumed: JDK 21 defaults
 * {@code file.encoding} to UTF-8 on every platform (JEP 400), and Maven
 * here already resolves {@code project.build.sourceEncoding} to UTF-8 --
 * confirmed via {@code mvn help:evaluate} before writing this class, not
 * inferred. {@code pom.xml} now pins that property explicitly anyway, so
 * this stays correct on a future machine or CI runner that isn't JDK 21.
 * {@link #GROUP_KEYWORD}'s codepoints (U+10EF, U+10D2, U+10E3, U+10E4,
 * U+10D8) are asserted directly in {@code DepartmentMatcherTest}, not just eyeballed.
 */
public final class DepartmentMatcher {

    private static final String GROUP_KEYWORD = "ჯგუფი"; // "ჯგუფი"

    /**
     * The target value that means "everyone". Public because
     * {@link #visibilityTargets} puts it in a list callers pass to a query,
     * and a second spelling of it somewhere else would be a silent hole.
     * {@code ManagerScope} keeps its own copy on purpose — there the same
     * string must <b>not</b> act as a wildcard, and its javadoc says why.
     */
    public static final String WILDCARD_TARGET = "All";

    /** Public: the department dashboard reuses this same delimiter to reconstruct a full department string. */
    public static final String CANONICAL_DELIMITER = "—"; // em dash "—"

    private static final Pattern DASH_PATTERN = Pattern.compile("\\s*[-–—]\\s*");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t]+");
    private static final Pattern TRAILING_DASH_RUN = Pattern.compile("[\\s-–—]+$");

    private DepartmentMatcher() {
    }

    /** Collapses whitespace runs and strips the ends, but leaves dashes untouched. */
    public static String normalize(String raw) {
        String stripped = raw == null ? "" : raw.strip();
        return WHITESPACE_RUN.matcher(stripped).replaceAll(" ");
    }

    /**
     * Splits a raw department string into (prefix, group label), handling
     * em dash, en dash, ASCII hyphen, missing spaces, extra whitespace, and
     * the "ჯგუფი" keyword as a fallback delimiter when no dash is present --
     * in that exact priority order.
     */
    public static DepartmentGroup splitGroup(String rawDepartment) {
        String raw = normalize(rawDepartment);
        if (raw.isEmpty()) {
            return new DepartmentGroup("", "");
        }

        int delimiterIndex = raw.indexOf(CANONICAL_DELIMITER);
        if (delimiterIndex >= 0) {
            String prefix = raw.substring(0, delimiterIndex).strip();
            String suffix = raw.substring(delimiterIndex + CANONICAL_DELIMITER.length()).strip();
            return new DepartmentGroup(prefix, suffix.isEmpty() ? prefix : suffix);
        }

        int keywordIndex = raw.indexOf(GROUP_KEYWORD);
        if (keywordIndex > 0) {
            String prefix = TRAILING_DASH_RUN.matcher(raw.substring(0, keywordIndex)).replaceAll("");
            String suffix = raw.substring(keywordIndex).strip();
            return new DepartmentGroup(prefix, suffix);
        }

        String[] parts = DASH_PATTERN.split(raw, 2);
        if (parts.length == 2) {
            String prefix = parts[0].strip();
            String suffix = parts[1].strip();
            return new DepartmentGroup(prefix, suffix.isEmpty() ? prefix : suffix);
        }

        return new DepartmentGroup(raw, raw);
    }

    /**
     * The target-department values a caller's own content is delivered by:
     * their department, its parent prefix, and the {@code "All"} wildcard.
     *
     * <p>Six places built this list inline as
     * {@code List.of(user.getDepartment(), prefix, "All")} — article and news
     * lists, required readings on an article, the compliance page and two
     * halves of the home page. {@code List.of} rejects a null element, and
     * {@code users.department} is nullable ({@code V3__create_users.sql:13}),
     * so a caller with no department did not get an empty list or a refusal:
     * all six threw {@code NullPointerException} and answered 500.
     *
     * <p>Nobody hit it, for a reason with an expiry date. Every account today
     * is created by the development JIT path, which always writes a
     * department, and {@code PUT /api/users/{id}} refuses to blank one. Real
     * accounts will arrive from Active Directory instead, where the attribute
     * is routinely empty — so this is a crash waiting on a feature rather
     * than a theoretical one.
     *
     * <p>A null department resolves to {@code ["All"]}, which is the same
     * answer {@link #matches} already gives such a caller: wildcard-targeted
     * content and nothing else. The list is deduplicated, so a department with
     * no group suffix does not repeat itself; for every non-null input the
     * result is otherwise exactly what the inline expression produced.
     */
    public static List<String> visibilityTargets(String rawDepartment) {
        List<String> targets = new ArrayList<>(3);
        if (rawDepartment != null) {
            targets.add(rawDepartment);
        }
        String prefix = splitGroup(rawDepartment).prefix();
        if (!prefix.isEmpty() && !targets.contains(prefix)) {
            targets.add(prefix);
        }
        if (!targets.contains(WILDCARD_TARGET)) {
            targets.add(WILDCARD_TARGET);
        }
        return List.copyOf(targets);
    }

    /**
     * True if {@code userDepartment} matches any of {@code targets}, with
     * prefix support for sub-groups (a target of "ტექნიკური" matches a user
     * department of "ტექნიკური — ჯგუფი 03") -- the exact-match
     * check runs unconditionally while the prefix check additionally
     * requires a non-null, non-empty target.
     */
    public static boolean matches(String userDepartment, Collection<String> targets) {
        DepartmentGroup userGroup = splitGroup(userDepartment);
        for (String target : targets) {
            if (WILDCARD_TARGET.equals(target)) {
                return true;
            }
            boolean exactMatch = Objects.equals(userDepartment, target);
            boolean prefixMatch = target != null && !target.isEmpty() && target.equals(userGroup.prefix());
            if (exactMatch || prefixMatch) {
                return true;
            }
        }
        return false;
    }
}
