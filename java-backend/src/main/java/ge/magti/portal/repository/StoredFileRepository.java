package ge.magti.portal.repository;

import ge.magti.portal.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Keyed on the generated {@code <uuid>.<ext>} filename -- see
 * {@link StoredFile}'s javadoc for why that, and not a surrogate id, is the
 * primary key.
 */
public interface StoredFileRepository extends JpaRepository<StoredFile, String> {
}
