package ge.magti.portal.util;

/**
 * The (prefix, group_label) pair a department string splits into. See
 * {@link DepartmentMatcher#splitGroup(String)}.
 */
public record DepartmentGroup(String prefix, String groupLabel) {
}
