package ge.magti.portal.web;

import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.security.PermissionChecker;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors routers/categories.py's 4 endpoints: list (active-only, every
 * role alike -- unlike videos, there is no admin-sees-inactive-too branch
 * here), and admin create/update/delete.
 *
 * <p>Same two-gate auth shape as {@link VideoController}: no/invalid token
 * (401, English), wrong role (403, English -- security.py's
 * {@code get_current_admin_user} = {@code require_roles(content_admin,
 * admin)}). There is no granular sub-permission here, unlike videos'
 * archive endpoints.
 *
 * <p><b>Known, deliberate gap</b> (same as {@link VideoController}):
 * Python's create/update/delete get an automatic audit row from
 * audit_trail.py's ORM listener (models.Category is in its classified-model
 * map) -- no Java equivalent of that cross-cutting mechanism exists yet, so
 * none of the three mutating endpoints below write an audit row.
 *
 * <p>Also not ported: state.py's category_cache/search_cache TTL-cache
 * clearing -- no cache exists in the Java port yet.
 *
 * <p><b>Delete's fallback reassignment</b> (routers/categories.py:104-142):
 * deleting a category is a soft-delete ({@code is_active=false}), but any
 * article still pointing at it would otherwise become orphaned, so its
 * articles are first bulk-reassigned to a fixed fallback category named
 * "ზოგადი" ("General"), created on the fly with fixed defaults if it
 * doesn't exist yet. If the category being deleted IS that fallback
 * category, the reassignment step is skipped (a category can't be
 * reassigned onto itself).
 *
 * <p><b>Bug fix, found during a full-suite test run (2026-08-12):</b> the
 * fallback lookup used {@code findByName} (single-result semantics), which
 * throws if two categories ever share the "ზოგადი" name -- a real
 * possibility since {@code categories.name} has no unique constraint, in
 * either app. This would 500 on ANY category delete, not just deleting the
 * fallback itself. Python's {@code .filter(name==...).first()}
 * (routers/categories.py:127) degrades gracefully instead, silently using
 * whichever row it finds first -- {@code findFirstByNameOrderByIdAsc} now
 * matches that.
 */
@RestController
public class CategoryController {

    private static final String NOT_FOUND_DETAIL = "კატეგორია ვერ მოიძებნა";
    private static final String FALLBACK_NAME = "ზოგადი";

    private final CategoryRepository categoryRepository;
    private final ArticleRepository articleRepository;
    private final PermissionChecker permissionChecker;

    public CategoryController(
            CategoryRepository categoryRepository, ArticleRepository articleRepository,
            PermissionChecker permissionChecker) {
        this.categoryRepository = categoryRepository;
        this.articleRepository = articleRepository;
        this.permissionChecker = permissionChecker;
    }

    @GetMapping("/api/categories")
    public ResponseEntity<?> getCategories(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<CategoryResponse> categories = categoryRepository.findAll().stream()
                .filter(Category::isActive)
                .map(CategoryResponse::from)
                .toList();
        return ResponseEntity.ok(categories);
    }

    @PostMapping("/api/categories")
    public ResponseEntity<?> createCategory(
            @Valid @RequestBody CategoryRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }

        // BL-08: categories.name has no unique constraint (V2, a deliberate
        // parity decision with Python) and nothing checked for duplicates,
        // so two categories called "ტექნიკური" were indistinguishable in
        // every dropdown in the product -- an editor picking one had no way
        // to know which.
        //
        // Decision, recorded because the audit says this question has now
        // been raised twice: an application-level check, NOT a unique
        // constraint. A constraint would need a cleanup migration to merge
        // or rename whatever duplicates already exist in the live database,
        // which is a data decision nobody can make from here; and it would
        // have to reckon with soft-deleted rows, which SHOULD be allowed to
        // share a name with a live one. The check below is scoped to active
        // categories for exactly that reason. Revisit if IT confirms the
        // production data is clean.
        ResponseEntity<Map<String, String>> duplicate = rejectDuplicateName(request.name(), null);
        if (duplicate != null) {
            return duplicate;
        }

        Category category = new Category();
        applyRequest(category, request);
        Category saved = categoryRepository.save(category);
        return ResponseEntity.ok(CategoryResponse.from(saved));
    }

    @PutMapping("/api/categories/{id}")
    public ResponseEntity<?> updateCategory(
            @PathVariable Long id, @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }

        Optional<Category> found = categoryRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Category category = found.get();
        ResponseEntity<Map<String, String>> duplicate = rejectDuplicateName(request.name(), id);
        if (duplicate != null) {
            return duplicate;
        }
        applyRequest(category, request);
        Category saved = categoryRepository.save(category);
        return ResponseEntity.ok(CategoryResponse.from(saved));
    }

    @DeleteMapping("/api/categories/{id}")
    @Transactional
    public ResponseEntity<?> deleteCategory(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }

        Optional<Category> found = categoryRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Category category = found.get();

        // BL-07: active-only. The old lookup would happily return the
        // fallback category AFTER it had itself been deleted, sending every
        // later deletion's articles into a category getCategories hides.
        Category fallback = categoryRepository.findFirstByNameAndActiveTrueOrderByIdAsc(FALLBACK_NAME).orElseGet(() -> {
            Category created = new Category();
            created.setName(FALLBACK_NAME);
            created.setSlug("general");
            created.setIcon("fa-layer-group");
            created.setPastelColorClass("general");
            created.setActive(true);
            return categoryRepository.saveAndFlush(created);
        });

        if (!fallback.getId().equals(id)) {
            articleRepository.reassignCategory(id, fallback.getId());
        }

        category.setActive(false);
        categoryRepository.save(category);
        return ResponseEntity.noContent().build();
    }

    /**
     * BL-08. Scoped to ACTIVE categories: a soft-deleted row should be
     * allowed to keep a name a live one now uses, or deleting and recreating
     * a category would be impossible.
     *
     * @param excludeId the category being updated, so renaming it to its own
     *                  current name is not a conflict with itself
     */
    private ResponseEntity<Map<String, String>> rejectDuplicateName(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Optional<Category> existing = categoryRepository.findFirstByNameIgnoreCaseAndActiveTrue(name.strip());
        if (existing.isEmpty() || existing.get().getId().equals(excludeId)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("detail", "ამ სახელით კატეგორია უკვე არსებობს: " + name.strip()));
    }

    private void applyRequest(Category category, CategoryRequest request) {
        String normalizedName = request.name().strip();
        category.setName(normalizedName);
        category.setParentId(request.parentId());
        category.setSlug(resolveSlug(request.slug(), normalizedName, category.getId()));
        category.setIcon(request.icon());
        category.setPastelColorClass(request.pastelColorClass());
        category.setActive(request.isActiveOrDefault());
    }

    /**
     * Makes a usable route mandatory even when the admin leaves the optional
     * slug field empty. Georgian letters are deliberately retained: Angular
     * encodes them safely in the URL, while transliteration would introduce
     * a second naming system administrators would have to learn.
     *
     * <p>There is no database unique constraint because historical data
     * already contains duplicate slugs. New active rows are nevertheless
     * made unambiguous by adding a numeric suffix when necessary.
     */
    private String resolveSlug(String requestedSlug, String categoryName, Long excludeId) {
        String source = requestedSlug == null || requestedSlug.isBlank() ? categoryName : requestedSlug;
        String base = slugify(source);
        if (base.isBlank()) {
            base = "category";
        }

        String candidate = base;
        int suffix = 2;
        while (isActiveSlugConflict(candidate, excludeId)) {
            String suffixText = "-" + suffix++;
            candidate = truncate(base, 150 - suffixText.length()) + suffixText;
        }
        return candidate;
    }

    private boolean isActiveSlugConflict(String slug, Long excludeId) {
        Optional<Category> existing = categoryRepository.findFirstBySlugIgnoreCaseAndActiveTrue(slug);
        return existing.isPresent() && !existing.get().getId().equals(excludeId);
    }

    private static String slugify(String source) {
        String normalized = Normalizer.normalize(source.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        return truncate(normalized, 150);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength).replaceAll("-+$", "");
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireContentManage(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }
}
