package ge.magti.portal.repository;

import ge.magti.portal.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByName(String name);

    /**
     * Same lookup as {@link #findByName}, but tolerant of duplicate names --
     * {@code categories.name} has never had a real unique constraint (see
     * V2__create_categories.sql, matching models.py's Category), so two
     * categories can legitimately share a name (e.g. two admins both
     * creating a "ზოგადი" category). Used where Python's SQLAlchemy
     * {@code .first()} degrades gracefully instead of crashing; findByName
     * (Spring Data's single-result semantics) throws
     * IncorrectResultSizeDataAccessException in that case instead.
     */
    Optional<Category> findFirstByNameOrderByIdAsc(String name);
}
