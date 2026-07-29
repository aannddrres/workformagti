package ge.magti.portal.util;

/**
 * Mirrors the (prefix, group_label) pair compliance_utils.py's
 * _split_dept_group returns (compliance_utils.py:43-80). See
 * {@link DepartmentMatcher#splitGroup(String)}.
 */
public record DepartmentGroup(String prefix, String groupLabel) {
}
