package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
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
 * <p>Create/update/delete write a reconstructable audit row in the same
 * transaction as the category change. An audit flush failure therefore rolls
 * the business mutation back instead of leaving an unaudited category state.
 *
 * <p>Also not ported: state.py's category_cache/search_cache TTL-cache
 * clearing -- no cache exists in the Java port yet.
 *
 * <p>R5 changes delete from silent fallback reassignment to fail-closed
 * blocking. The administrator must explicitly move every article first;
 * trashed articles count too because they may still be restored.
 */
@RestController
public class CategoryController {

    private static final String NOT_FOUND_DETAIL = "კატეგორია ვერ მოიძებნა";

    private final CategoryRepository categoryRepository;
    private final ArticleRepository articleRepository;
    private final PermissionChecker permissionChecker;
    private final MutationAuditService contentMutationAuditService;

    public CategoryController(
            CategoryRepository categoryRepository, ArticleRepository articleRepository,
            PermissionChecker permissionChecker, MutationAuditService contentMutationAuditService) {
        this.categoryRepository = categoryRepository;
        this.articleRepository = articleRepository;
        this.permissionChecker = permissionChecker;
        this.contentMutationAuditService = contentMutationAuditService;
    }

    @GetMapping("/api/categories")
    public ResponseEntity<?> getCategories(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<CategoryResponse> categories = CompleteResultGuard.enforce(
                        categoryRepository.findByActiveTrue(CompleteResultGuard.sentinelPage())).stream()
                .map(CategoryResponse::from)
                .toList();
        return ResponseEntity.ok(categories);
    }

    @PostMapping("/api/categories")
    @Transactional
    public ResponseEntity<?> createCategory(
            @Valid @RequestBody CategoryRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
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
        Category saved = categoryRepository.saveAndFlush(category);
        contentMutationAuditService.recordSuccess(
                user, "CREATE", "category", saved.getId(), saved.getName(), null,
                MutationAuditService.categorySnapshot(saved));
        return ResponseEntity.ok(CategoryResponse.from(saved));
    }

    @PutMapping("/api/categories/{id}")
    @Transactional
    public ResponseEntity<?> updateCategory(
            @PathVariable Long id, @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        Optional<Category> found = categoryRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Category category = found.get();
        Map<String, Object> before = MutationAuditService.categorySnapshot(category);
        ResponseEntity<Map<String, String>> duplicate = rejectDuplicateName(request.name(), id);
        if (duplicate != null) {
            return duplicate;
        }
        applyRequest(category, request);
        Category saved = categoryRepository.saveAndFlush(category);
        contentMutationAuditService.recordSuccess(
                user, "UPDATE", "category", saved.getId(), saved.getName(), before,
                MutationAuditService.categorySnapshot(saved));
        return ResponseEntity.ok(CategoryResponse.from(saved));
    }

    @DeleteMapping("/api/categories/{id}")
    @Transactional
    public ResponseEntity<?> deleteCategory(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        Optional<Category> found = categoryRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        if (articleRepository.countAllByCategoryIdIncludingTrash(id) > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "detail", "კატეგორია გამოიყენება — ჯერ ყველა მასალა სხვა კატეგორიაში გადაიტანეთ"));
        }
        // Same dangling-reference guard as the article check above, for the other
        // thing that points at a category: its own subcategories. Without this a
        // parent could be deactivated while its children stayed active, still
        // carrying parent_id -- and since the category list is active-only, the
        // UI then renders those children as orphans pointing at a parent it
        // cannot find. Nothing is corrupted (the parent row survives, soft
        // deleted) but the tree is inconsistent until someone reactivates it.
        if (categoryRepository.countByParentIdAndActiveTrue(id) > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "detail", "კატეგორიას ქვეკატეგორიები აქვს — ჯერ ისინი წაშალეთ ან სხვა კატეგორიას დაუქვემდებარეთ"));
        }

        Category category = found.get();
        Map<String, Object> before = MutationAuditService.categorySnapshot(category);
        category.setActive(false);
        Category saved = categoryRepository.saveAndFlush(category);
        contentMutationAuditService.recordSuccess(
                user, "DELETE", "category", saved.getId(), saved.getName(), before,
                MutationAuditService.categorySnapshot(saved));
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

}
