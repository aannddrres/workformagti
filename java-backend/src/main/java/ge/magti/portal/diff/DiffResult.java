package ge.magti.portal.diff;

/** Mirrors diffing.py's diff_html return dict: {'html', 'added', 'removed'}. */
public record DiffResult(String html, int added, int removed) {
}
