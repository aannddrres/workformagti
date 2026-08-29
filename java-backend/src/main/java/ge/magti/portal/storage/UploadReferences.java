package ge.magti.portal.storage;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What counts as "this content references that uploaded file".
 *
 * <p>Extracted here so the two places that ask now -- purge (which must not
 * delete a file something still points at) and authorization (which must not
 * hand out a file nothing the caller can read points at) -- cannot drift into
 * disagreeing. A file the purge logic considers referenced and the access
 * logic does not would be unreachable but undeletable; the reverse would
 * delete a file still on a page.
 *
 * <p>The pattern is deliberately permissive about what surrounds the name.
 * Content is authored HTML: the same file appears as {@code src="/uploads/x"},
 * inside a {@code srcset}, behind a query string, and occasionally with a
 * backslash from a Windows paste. Matching the {@code uploads/} segment and
 * taking the following name catches all of them, and a false positive here is
 * harmless in both callers -- it keeps a file alive, or grants access to a
 * file the page really does mention.
 */
public final class UploadReferences {

    private static final Pattern UPLOAD_REFERENCE = Pattern.compile(
            "(?:^|[/\\\\])uploads[/\\\\]([A-Za-z0-9._-]{1,100})(?:[?#][^\\\"'\\s<]*)?",
            Pattern.CASE_INSENSITIVE);

    private UploadReferences() {
    }

    /** Every stored filename mentioned by the given texts, in first-seen order. */
    public static Set<String> in(String... texts) {
        Set<String> filenames = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher matcher = UPLOAD_REFERENCE.matcher(text);
            while (matcher.find()) {
                filenames.add(matcher.group(1));
            }
        }
        return filenames;
    }
}
