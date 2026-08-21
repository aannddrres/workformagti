package ge.magti.portal.domain;

/**
 * Which kind of scope a {@link LeadershipAssignment} covers.
 *
 * <p>Derived from which foreign key is set, never stored: the table keeps two
 * nullable real FKs plus a CHECK that exactly one is populated, so the column
 * a polymorphic {@code scope_type} would occupy cannot disagree with the data
 * it describes.
 */
public enum LeadershipScope {
    DEPARTMENT,
    GROUP
}
