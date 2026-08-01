package ge.magti.portal.diff;

/**
 * Shaped like Python difflib's SequenceMatcher.get_opcodes() tuples --
 * {@code (op, i1, i2, j1, j2)}, {@code op} one of "equal"/"delete"/
 * "insert"/"replace" -- so {@link HtmlDiffer}'s block- and word-level diff
 * loops can stay structurally close to diffing.py's, even though the
 * opcodes are reconstructed from java-diff-utils' Patch rather than
 * SequenceMatcher itself.
 */
public record DiffOpcode(String op, int i1, int i2, int j1, int j2) {
}
