package ge.magti.portal.diff;

/** An HTML diff: the marked-up html plus the added and removed counts. */
public record DiffResult(String html, int added, int removed) {
}
