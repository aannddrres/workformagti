package ge.magti.portal.stats;

/**
 * Ports the {@code name.split(None, 1)} idiom used twice in routers/stats.py
 * (get_critical_operators:730, get_group_users:801) to split a display name
 * into (first, last). Python's {@code split(None, ...)} has non-obvious
 * behavior, verified empirically against real Python output rather than
 * assumed, before writing this:
 * <ul>
 *   <li>leading whitespace is skipped before finding the first token</li>
 *   <li>the split happens on the *first* whitespace run only</li>
 *   <li>whatever follows that run becomes the second element <b>verbatim</b>
 *       -- internal and trailing whitespace inside it are NOT stripped
 *       ("Nika  Agdgomelashvili  " -&gt; ["Nika", "Agdgomelashvili  "])</li>
 *   <li>if nothing follows the first whitespace run (e.g. "Nika " -- just a
 *       single word plus trailing space), the result is a single-element
 *       array, not a second empty-string element</li>
 *   <li>blank or all-whitespace input produces a zero-length array, matching
 *       {@code parts[0] if parts else ""} needing that empty-array case</li>
 * </ul>
 *
 * <p>Uses {@link Character#isWhitespace(char)} to decide what counts as a
 * separator. This is not byte-for-byte identical to Python's whitespace
 * definition (e.g. Java's isWhitespace excludes non-breaking space U+00A0,
 * which Python's does treat as whitespace) -- a low-risk gap for operator
 * display names, called out here rather than silently assumed identical.
 */
public final class DisplayName {

    private DisplayName() {
    }

    /** Returns a 0, 1, or 2-element array: [] if blank, [first] or [first, rest]. */
    public static String[] splitFirstLast(String name) {
        String input = name == null ? "" : name;
        int length = input.length();

        int start = 0;
        while (start < length && Character.isWhitespace(input.charAt(start))) {
            start++;
        }
        if (start == length) {
            return new String[0];
        }

        int firstTokenEnd = start;
        while (firstTokenEnd < length && !Character.isWhitespace(input.charAt(firstTokenEnd))) {
            firstTokenEnd++;
        }
        if (firstTokenEnd == length) {
            return new String[] {input.substring(start)};
        }

        int restStart = firstTokenEnd;
        while (restStart < length && Character.isWhitespace(input.charAt(restStart))) {
            restStart++;
        }
        if (restStart == length) {
            return new String[] {input.substring(start, firstTokenEnd)};
        }

        return new String[] {input.substring(start, firstTokenEnd), input.substring(restStart)};
    }

    /** Mirrors {@code parts[0] if parts else ""}. */
    public static String firstName(String[] parts) {
        return parts.length > 0 ? parts[0] : "";
    }

    /** Mirrors {@code parts[1] if len(parts) > 1 else ""}. */
    public static String lastName(String[] parts) {
        return parts.length > 1 ? parts[1] : "";
    }
}
