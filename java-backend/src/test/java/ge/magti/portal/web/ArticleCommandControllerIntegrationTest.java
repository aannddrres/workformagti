package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class ArticleCommandControllerIntegrationTest {
    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Autowired UserRepository users;
    @Autowired UserPermissionOverrideRepository permissionOverrides;
    @Autowired CategoryRepository categories;
    @Autowired ArticleRepository articles;
    @Autowired RequiredReadingRepository requiredReadings;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;

    private User contentAdmin() {
        User user = new User();
        user.setEmail("command-admin-" + System.nanoTime() + "@magti.ge");
        user.setName("ატომური ბრძანების ადმინი");
        user.setRole(Role.CONTENT_ADMIN);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(Role.CONTENT_ADMIN).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return users.saveAndFlush(user);
    }

    private Category createCategory() {
        Category category = new Category();
        category.setName("command-category-" + System.nanoTime());
        category.setActive(true);
        return categories.saveAndFlush(category);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private Map<String, Object> article(String title, Long categoryId, boolean quizEnabled) {
        return Map.ofEntries(
                Map.entry("title", title),
                Map.entry("content", "<p>ატომური შენახვის ტესტი</p>"),
                Map.entry("category_id", categoryId),
                Map.entry("target_departments", List.of("All")),
                Map.entry("status", "published"),
                Map.entry("is_draft", false),
                Map.entry("quiz_enabled", quizEnabled));
    }

    private Article draftRow(String title, Long categoryId) {
        Article row = new Article();
        row.setTitle(title);
        row.setContent("საწყისი შინაარსი");
        row.setCategoryId(categoryId);
        row.setStatus("draft");
        // An editorial draft, which any editor may change. A private one
        // (is_draft) would be its author's alone (PO-34), and has none here.
        row.setDraft(false);
        row.setVersion(1);
        row.setCreatedAt(TbilisiTime.now());
        row.setUpdatedAt(TbilisiTime.now());
        return articles.saveAndFlush(row);
    }

    @Test
    void invalidQuizRollsBackTheArticleAndMandatoryAssignment() throws Exception {
        String title = "rollback-command-" + System.nanoTime();
        Category category = createCategory();
        User admin = contentAdmin();
        Map<String, Object> body = Map.of(
                "article", article(title, category.getId(), true),
                "mandatory", true,
                "due_date", OffsetDateTime.now().plusDays(3).toString(),
                "target_department", "All",
                "quiz", Map.of("questions", List.of()));

        mockMvc.perform(post("/api/articles/command")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isUnprocessableEntity());

        assertTrue(articles.findAll().stream().noneMatch(article -> title.equals(article.getTitle())),
                "the article must not survive when quiz validation fails");
        categories.deleteById(category.getId());
        users.deleteById(admin.getId());
    }

    @Test
    @Transactional
    void oneSuccessfulCommandCreatesTheArticleAndMandatoryAssignmentTogether() throws Exception {
        String title = "success-command-" + System.nanoTime();
        Category category = createCategory();
        User admin = contentAdmin();
        Map<String, Object> body = Map.of(
                "article", article(title, category.getId(), false),
                "mandatory", true,
                "due_date", OffsetDateTime.now().plusDays(3).toString(),
                "target_department", "All");

        long articleId = objectMapper.readTree(mockMvc.perform(post("/api/articles/command")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(title))
                .andReturn().getResponse().getContentAsByteArray()).get("id").asLong();

        assertTrue(requiredReadings.findFirstByItemTypeAndItemIdOrderByIdAsc("article", articleId).isPresent(),
                "the same transaction must create the mandatory assignment");
    }

    @Test
    @Transactional
    void createCommandRejectsRevokedEditPermissionWithoutWritingContentOrAssignment() throws Exception {
        Category category = createCategory();
        User admin = contentAdmin();
        UserPermissionOverride denied = new UserPermissionOverride();
        denied.setUserId(admin.getId());
        denied.setPermission(Permission.ARTICLES_EDIT.value());
        denied.setState(UserPermissionOverride.State.DENY);
        denied.setUpdatedAt(TbilisiTime.now());
        denied.setUpdatedBy(admin.getId());
        permissionOverrides.saveAndFlush(denied);
        String title = "denied-command-" + System.nanoTime();
        long articlesBefore = articles.count();
        long readingsBefore = requiredReadings.count();

        mockMvc.perform(post("/api/articles/command")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "article", article(title, category.getId(), false),
                                "mandatory", true,
                                "due_date", OffsetDateTime.now().plusDays(3).toString(),
                                "target_department", "All"))))
                .andExpect(status().isForbidden());

        assertEquals(articlesBefore, articles.count());
        assertEquals(readingsBefore, requiredReadings.count());
    }

    @Test
    @Transactional
    void updateCommandChangesTheArticleAndMandatoryAssignmentTogether() throws Exception {
        Category category = createCategory();
        User admin = contentAdmin();
        Article existing = draftRow("საწყისი", category.getId());
        Map<String, Object> body = Map.of(
                "article", article("განახლებული", category.getId(), false),
                "mandatory", true,
                "due_date", OffsetDateTime.now().plusDays(3).toString(),
                "target_department", "All");

        mockMvc.perform(put("/api/articles/" + existing.getId() + "/command")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("განახლებული"));

        assertEquals("განახლებული", articles.findById(existing.getId()).orElseThrow().getTitle());
        assertTrue(requiredReadings.findFirstByItemTypeAndItemIdOrderByIdAsc("article", existing.getId()).isPresent());
    }

    @Test
    @Transactional
    void updateCommandRejectsRevokedEditPermissionWithoutChangingTheArticle() throws Exception {
        Category category = createCategory();
        User admin = contentAdmin();
        Article existing = draftRow("საწყისი", category.getId());
        UserPermissionOverride denied = new UserPermissionOverride();
        denied.setUserId(admin.getId());
        denied.setPermission(Permission.ARTICLES_EDIT.value());
        denied.setState(UserPermissionOverride.State.DENY);
        denied.setUpdatedAt(TbilisiTime.now());
        denied.setUpdatedBy(admin.getId());
        permissionOverrides.saveAndFlush(denied);
        Map<String, Object> body = Map.of("article", article("არ უნდა შეინახოს", category.getId(), false),
                "mandatory", false);

        mockMvc.perform(put("/api/articles/" + existing.getId() + "/command")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isForbidden());

        assertEquals("საწყისი", articles.findById(existing.getId()).orElseThrow().getTitle());
        assertFalse(requiredReadings.findFirstByItemTypeAndItemIdOrderByIdAsc("article", existing.getId()).isPresent());
    }

    @Test
    void invalidUpdateQuizRollsBackTheArticleAndMandatoryAssignment() throws Exception {
        Category category = createCategory();
        User admin = contentAdmin();
        Article existing = draftRow("საწყისი", category.getId());
        Map<String, Object> body = Map.of(
                "article", article("არ უნდა შეინახოს", category.getId(), true),
                "mandatory", true,
                "due_date", OffsetDateTime.now().plusDays(3).toString(),
                "target_department", "All",
                "quiz", Map.of("questions", List.of()));
        try {
            mockMvc.perform(put("/api/articles/" + existing.getId() + "/command")
                            .header("Authorization", "Bearer " + tokenFor(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(body)))
                    .andExpect(status().isUnprocessableEntity());

            assertEquals("საწყისი", articles.findById(existing.getId()).orElseThrow().getTitle());
            assertEquals("draft", articles.findById(existing.getId()).orElseThrow().getStatus());
            assertFalse(requiredReadings.findFirstByItemTypeAndItemIdOrderByIdAsc("article", existing.getId()).isPresent(),
                    "a failed quiz must roll back both the edit and mandatory assignment");
        } finally {
            articles.deleteById(existing.getId());
            categories.deleteById(category.getId());
            users.deleteById(admin.getId());
        }
    }
}
