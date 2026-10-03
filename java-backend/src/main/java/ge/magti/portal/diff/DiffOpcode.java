package ge.magti.portal.diff;

/**
 * Shaped like difflib's SequenceMatcher.get_opcodes() tuples --
 * {@code (op, i1, i2, j1, j2)}, {@code op} one of "equal"/"delete"/
 * "insert"/"replace" -- the shape {@link HtmlDiffer}'s block- and
 * word-level diff loops work in. The opcodes are reconstructed from
 * java-diff-utils' Patch.
 */
public record DiffOpcode(String op, int i1, int i2, int j1, int j2) {
}
