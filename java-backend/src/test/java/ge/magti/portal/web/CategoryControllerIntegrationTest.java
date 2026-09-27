package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- same
 * infrastructure as {@link VideoControllerIntegrationTest}. {@code
 * @Transactional} rolls back every user/category/article row this test
 * creates.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private Category createCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(name.toLowerCase());
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private Article createArticle(String title, Long categoryId) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setCategoryId(categoryId);
        return articleRepository.saveAndFlush(article);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    @Test
    void anyAuthenticatedUserSeesOnlyActiveCategories() throws Exception {
        User operator = createUser("ca1@magti.ge", Role.OPERATOR);
        createCategory("აქტიური კატეგორია");
        Category inactive = createCategory("არააქტიური კატეგორია");
        inactive.setActive(false);
        categoryRepository.saveAndFlush(inactive);

        mockMvc.perform(authed(get("/api/categories"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItem("აქტიური კატეგორია")))
                .andExpect(jsonPath("$[*].name").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("არააქტიური კატეგორია"))));
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void operatorCannotCreateACategory() throws Exception {
        User operator = createUser("ca2@magti.ge", Role.OPERATOR);
        long before = categoryRepository.count();

        mockMvc.perform(authed(post("/api/categories"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        assertEquals(before, categoryRepository.count());
    }

    @Test
    void operatorCannotUpdateOrDeleteCategoryAndOriginalRowRemainsActive() throws Exception {
        User operator = createUser("category-denied-" + System.nanoTime() + "@magti.ge", Role.OPERATOR);
        Category category = createCategory("დაცული კატეგორია " + System.nanoTime());
        long auditBefore = auditLogRepository.count();

        mockMvc.perform(authed(put("/api/categories/" + category.getId()), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"არ უნდა შეიცვალოს\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/categories/" + category.getId()), tokenFor(operator)))
                .andExpect(status().isForbidden());

        Category reloaded = categoryRepository.findById(category.getId()).orElseThrow();
        assertEquals(category.getName(), reloaded.getName());
        assertTrue(reloaded.isActive());
        assertEquals(auditBefore, auditLogRepository.count());
    }

    @Test
    void contentAdminCrudLifecycle() throws Exception {
        User admin = createUser("ca3@magti.ge", Role.CONTENT_ADMIN);

        String createBody = mockMvc.perform(authed(post("/api/categories"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ახალი კატეგორია\",\"slug\":\"new-category\","
                                + "\"icon\":\"fa-flask\",\"pastel_color_class\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ახალი კატეგორია"))
                .andExpect(jsonPath("$.is_active").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(createBody).get("id").asLong();

        mockMvc.perform(authed(get("/api/categories"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.hasItem((int) id)));

        mockMvc.perform(authed(put("/api/categories/" + id), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"განახლებული კატეგორია\",\"slug\":\"renamed\","
                                + "\"icon\":\"fa-beaker\",\"pastel_color_class\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("განახლებული კატეგორია"))
                .andExpect(jsonPath("$.icon").value("fa-beaker"));

        mockMvc.perform(authed(delete("/api/categories/" + id), tokenFor(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(get("/api/categories"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) id))));

        var audits = auditLogRepository.findAll().stream()
                .filter(a -> "category".equals(a.getItemType()) && Long.valueOf(id).equals(a.getItemId()))
                .sorted(Comparator.comparing(AuditLog::getId))
                .toList();
        assertEquals(java.util.List.of("CREATE", "UPDATE", "DELETE"),
                audits.stream().map(AuditLog::getAction).toList());
        assertTrue(audits.stream().allMatch(a -> admin.getId().equals(a.getAdminId())
                        && admin.getName().equals(a.getAdminNameSnapshot())
                        && admin.getEmail().equals(a.getAdminEmailSnapshot())),
                "every category mutation must retain the actor snapshot");

        AuditLog updateAudit = audits.get(1);
        var updateDetails = new com.fasterxml.jackson.databind.ObjectMapper().readTree(updateAudit.getDetails());
        assertEquals("SUCCESS", updateDetails.path("result").asText());
        assertEquals("ახალი კატეგორია", updateDetails.path("before").path("name").asText());
        assertEquals("განახლებული კატეგორია", updateDetails.path("after").path("name").asText());

        AuditLog deleteAudit = audits.get(2);
        var deleteDetails = new com.fasterxml.jackson.databind.ObjectMapper().readTree(deleteAudit.getDetails());
        assertTrue(deleteDetails.path("before").path("active").asBoolean());
        assertTrue(!deleteDetails.path("after").path("active").asBoolean());
        assertEquals("განახლებული კატეგორია", deleteAudit.getItemNameSnapshot());
    }

    @Test
    void omittedSlugIsGeneratedFromTheGeorgianName() throws Exception {
        User admin = createUser("slug-generation@magti.ge", Role.CONTENT_ADMIN);
        String name = "ახალი ქართული კატეგორია " + System.nanoTime();
        String expectedSlug = name.replace(' ', '-');

        String response = mockMvc.perform(authed(post("/api/categories"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(expectedSlug))
                .andReturn().getResponse().getContentAsString();

        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("id").asLong();
        assertEquals(expectedSlug, categoryRepository.findById(id).orElseThrow().getSlug());
    }

    @Test
    void editingAHistoricalCategoryWithNoSlugRepairsItsRoute() throws Exception {
        User admin = createUser("slug-repair@magti.ge", Role.CONTENT_ADMIN);
        Category historical = createCategory("ძველი კატეგორია " + System.nanoTime());
        historical.setSlug(null);
        categoryRepository.saveAndFlush(historical);

        String renamed = historical.getName() + " განახლებული";
        mockMvc.perform(authed(put("/api/categories/" + historical.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + renamed + "\",\"slug\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(renamed.replace(' ', '-')));
    }

    @Test
    void updatingAndDeletingAMissingCategoryIs404() throws Exception {
        User admin = createUser("ca4@magti.ge", Role.CONTENT_ADMIN);

        mockMvc.perform(authed(put("/api/categories/999999999"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("კატეგორია ვერ მოიძებნა"));

        mockMvc.perform(authed(delete("/api/categories/999999999"), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("კატეგორია ვერ მოიძებნა"));
    }

    @Test
    void deletingAUsedCategoryIsBlockedUntilContentIsMoved() throws Exception {
        User admin = createUser("ca5@magti.ge", Role.CONTENT_ADMIN);
        Category source = createCategory("წყარო კატეგორია");
        Article article = createArticle("გადასანაცვლებელი სტატია", source.getId());

        mockMvc.perform(authed(delete("/api/categories/" + source.getId()), tokenFor(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "კატეგორია გამოიყენება — ჯერ ყველა მასალა სხვა კატეგორიაში გადაიტანეთ"));

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(source.getId(), reloaded.getCategoryId());

        Category reloadedSource = categoryRepository.findById(source.getId()).orElseThrow();
        assertTrue(reloadedSource.isActive());
    }

    /**
     * Found by an AI-browser QA pass (2026-08-28). Deleting a parent used to
     * return 204 and deactivate it while its children stayed active, still
     * carrying parent_id. The category list is active-only, so the UI then
     * showed those children as orphans pointing at a parent it could not
     * find. The article guard above already prevented exactly this shape of
     * dangling reference; subcategories were simply not covered by it.
     */
    @Test
    void deletingAParentCategoryIsBlockedWhileItStillHasSubcategories() throws Exception {
        User admin = createUser("ca6@magti.ge", Role.CONTENT_ADMIN);
        Category parent = createCategory("მშობელი კატეგორია");
        Category child = createCategory("შვილი კატეგორია");
        child.setParentId(parent.getId());
        categoryRepository.saveAndFlush(child);

        mockMvc.perform(authed(delete("/api/categories/" + parent.getId()), tokenFor(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "კატეგორიას ქვეკატეგორიები აქვს — ჯერ ისინი წაშალეთ ან სხვა კატეგორიას დაუქვემდებარეთ"));

        assertTrue(categoryRepository.findById(parent.getId()).orElseThrow().isActive());

        // Once the child is gone the parent deletes normally -- the guard
        // blocks the dangling state, it does not make parents undeletable.
        mockMvc.perform(authed(delete("/api/categories/" + child.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        mockMvc.perform(authed(delete("/api/categories/" + parent.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        assertTrue(!categoryRepository.findById(parent.getId()).orElseThrow().isActive());
    }

    /**
     * Bug found running the full suite (2026-08-12): this dev Oracle schema
     * already has a real "ზოგადი" category left over from earlier manual
     * browser verification of the Categories admin UI -- categories.name
     * has no unique constraint (see CategoryController's class doc), so
     * blindly creating a second row with that name here made the
     * controller's fallback lookup throw IncorrectResultSizeDataAccessException
     * on ANY delete, not just this test's own. Find-or-create instead,
     * exactly mirroring what the controller itself now does (also fixed),
     * so this test works whether or not a fallback already exists.
     */
    @Test
    void deletingAnyUsedCategoryIsBlockedWithoutFallbackSpecialCases() throws Exception {
        User admin = createUser("ca6@magti.ge", Role.CONTENT_ADMIN);
        Category fallback = categoryRepository.findFirstByNameOrderByIdAsc("ზოგადი")
                .orElseGet(() -> createCategory("ზოგადი"));
        Article article = createArticle("ზოგად კატეგორიაზე მიბმული სტატია", fallback.getId());

        mockMvc.perform(authed(delete("/api/categories/" + fallback.getId()), tokenFor(admin)))
                .andExpect(status().isConflict());

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(fallback.getId(), reloaded.getCategoryId());
    }

    /**
     * BL-08: categories.name has no unique constraint (V2, a deliberate
     * parity decision) and nothing checked for duplicates, so two categories
     * called the same thing were indistinguishable in every dropdown in the
     * product -- an editor picking one had no way to tell which.
     */
    @Test
    void aDuplicateActiveCategoryNameIsRejected() throws Exception {
        User admin = createUser("bl08a@magti.ge", Role.CONTENT_ADMIN);
        String name = "უნიკალური კატეგორია " + System.nanoTime();
        createCategory(name);
        long before = categoryRepository.count();

        mockMvc.perform(authed(post("/api/categories"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"slug\":\"dupe\","
                                + "\"icon\":\"fa-flask\",\"pastel_color_class\":\"general\"}"))
                .andExpect(status().isConflict());
        assertEquals(before, categoryRepository.count());
    }

    /** Case is not a distinction a user can see in a dropdown, so it is not one here either. */
    @Test
    void aDuplicateDifferingOnlyInCaseIsAlsoRejected() throws Exception {
        User admin = createUser("bl08b@magti.ge", Role.CONTENT_ADMIN);
        String name = "Mixed Case Category " + System.nanoTime();
        createCategory(name);

        mockMvc.perform(authed(post("/api/categories"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name.toUpperCase() + "\",\"slug\":\"dupe2\","
                                + "\"icon\":\"fa-flask\",\"pastel_color_class\":\"general\"}"))
                .andExpect(status().isConflict());
    }

    /** Renaming a category to the name it already has must not conflict with itself. */
    @Test
    void updatingACategoryWithoutChangingItsNameIsAllowed() throws Exception {
        User admin = createUser("bl08c@magti.ge", Role.CONTENT_ADMIN);
        String name = "თვითკონფლიქტი " + System.nanoTime();
        Category existing = createCategory(name);

        mockMvc.perform(authed(put("/api/categories/" + existing.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"slug\":\"same\","
                                + "\"icon\":\"fa-beaker\",\"pastel_color_class\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon").value("fa-beaker"));
    }

    /**
     * BL-07: the fallback lookup ignored is_active, so once "ზოგადი" had
     * itself been deleted, every later deletion reassigned its articles INTO
     * that inactive row -- and getCategories hides inactive categories, so
     * the articles landed somewhere nobody can see or select.
     */
    @Test
    void deletingAnUnusedCategoryStillSoftDeletesIt() throws Exception {
        User admin = createUser("bl07@magti.ge", Role.CONTENT_ADMIN);
        Category doomed = createCategory("წასაშლელი " + System.nanoTime());

        mockMvc.perform(authed(delete("/api/categories/" + doomed.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        assertTrue(!categoryRepository.findById(doomed.getId()).orElseThrow().isActive());
    }
}
