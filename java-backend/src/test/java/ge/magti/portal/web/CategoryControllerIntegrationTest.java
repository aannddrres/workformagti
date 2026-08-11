package ge.magti.portal.web;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
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

        mockMvc.perform(authed(post("/api/categories"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Not enough permissions to perform this action"));
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
    void deletingACategoryReassignsItsArticlesToTheFallback() throws Exception {
        User admin = createUser("ca5@magti.ge", Role.CONTENT_ADMIN);
        Category source = createCategory("წყარო კატეგორია");
        Article article = createArticle("გადასანაცვლებელი სტატია", source.getId());

        mockMvc.perform(authed(delete("/api/categories/" + source.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());

        Category fallback = categoryRepository.findFirstByNameOrderByIdAsc("ზოგადი").orElseThrow();
        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(fallback.getId(), reloaded.getCategoryId());

        Category reloadedSource = categoryRepository.findById(source.getId()).orElseThrow();
        assertTrue(!reloadedSource.isActive());
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
    void deletingTheFallbackCategoryItselfSkipsReassignment() throws Exception {
        User admin = createUser("ca6@magti.ge", Role.CONTENT_ADMIN);
        Category fallback = categoryRepository.findFirstByNameOrderByIdAsc("ზოგადი")
                .orElseGet(() -> createCategory("ზოგადი"));
        Article article = createArticle("ზოგად კატეგორიაზე მიბმული სტატია", fallback.getId());

        mockMvc.perform(authed(delete("/api/categories/" + fallback.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(fallback.getId(), reloaded.getCategoryId());
    }
}
