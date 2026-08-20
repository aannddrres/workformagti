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

    /**
     * BL-07: deleteCategory's fallback lookup used
     * {@link #findFirstByNameOrderByIdAsc}, which ignores {@code is_active}.
     * Once the fallback category was itself deleted (soft-deleted --
     * {@code active = false}), every later deletion happily reassigned its
     * articles INTO that inactive row, and getCategories filters inactive
     * categories out -- so the articles landed in a category no one can see
     * or select. Active-only, so a soft-deleted fallback is treated as
     * absent and a fresh one is created.
     */
    Optional<Category> findFirstByNameAndActiveTrueOrderByIdAsc(String name);

    /** BL-08: duplicate-name guard for create/update. Case-insensitive, since two categories differing only in case are indistinguishable to a user reading a dropdown. */
    Optional<Category> findFirstByNameIgnoreCaseAndActiveTrue(String name);

    /** Active category routes must be unambiguous; inactive rows do not appear in the public category list. */
    Optional<Category> findFirstBySlugIgnoreCaseAndActiveTrue(String slug);
}
