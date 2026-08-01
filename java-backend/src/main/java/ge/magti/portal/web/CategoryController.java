package ge.magti.portal.web;

import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
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

import java.util.List;
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
 */
@RestController
public class CategoryController {

    private static final String NOT_FOUND_DETAIL = "კატეგორია ვერ მოიძებნა";
    private static final String FALLBACK_NAME = "ზოგადი";

    private final CategoryRepository categoryRepository;
    private final ArticleRepository articleRepository;

    public CategoryController(CategoryRepository categoryRepository, ArticleRepository articleRepository) {
        this.categoryRepository = categoryRepository;
        this.articleRepository = articleRepository;
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
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
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
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<Category> found = categoryRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Category category = found.get();
        applyRequest(category, request);
        Category saved = categoryRepository.save(category);
        return ResponseEntity.ok(CategoryResponse.from(saved));
    }

    @DeleteMapping("/api/categories/{id}")
    @Transactional
    public ResponseEntity<?> deleteCategory(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<Category> found = categoryRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Category category = found.get();

        Category fallback = categoryRepository.findByName(FALLBACK_NAME).orElseGet(() -> {
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

    private static void applyRequest(Category category, CategoryRequest request) {
        category.setName(request.name());
        category.setParentId(request.parentId());
        category.setSlug(request.slug());
        category.setIcon(request.icon());
        category.setPastelColorClass(request.pastelColorClass());
        category.setActive(request.isActiveOrDefault());
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

    private static ResponseEntity<Map<String, String>> requireContentAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!user.getRole().isContentAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }
}
