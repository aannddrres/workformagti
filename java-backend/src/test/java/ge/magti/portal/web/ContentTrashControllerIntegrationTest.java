package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.sql.Timestamp;
import java.util.stream.Collectors;

import static ge.magti.portal.content.ContentLifecycleService.ItemType.ARTICLE;
import static ge.magti.portal.content.ContentLifecycleService.Status.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest(properties =
        "portal.retention.legal-hold-authorized-emails=legal-hold-authority@example.test")
@AutoConfigureMockMvc
@Transactional
class ContentTrashControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ContentLifecycleService lifecycleService;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void contentManagersCanListAndRestoreButOnlySystemAdminsCanPurge() throws Exception {
        User contentManager = user("trash-content", Role.CONTENT_ADMIN);
        User operator = user("trash-operator", Role.OPERATOR);
        User systemAdmin = user("trash-system", Role.SYSTEM_ADMIN);
        Article article = archivedArticle();

        assertEquals(OK, lifecycleService.moveToTrash(ARTICLE, article.getId(), contentManager));

        mockMvc.perform(get("/api/content-trash"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/content-trash").header("Authorization", bearer(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/content-trash").header("Authorization", bearer(contentManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.item_type == 'article' && @.item_id == "
                        + article.getId() + ")].title").value("აღდგენადი მასალა"));

        mockMvc.perform(post("/api/content-trash/article/" + article.getId() + "/restore")
                        .header("Authorization", bearer(operator)))
                .andExpect(status().isForbidden());
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .anyMatch(item -> item.itemId().equals(article.getId())));

        mockMvc.perform(post("/api/content-trash/article/" + article.getId() + "/restore")
                        .header("Authorization", bearer(contentManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("მასალა აღდგენილია"));
        assertTrue(articleRepository.findById(article.getId()).isPresent());
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .noneMatch(item -> item.itemId().equals(article.getId())));

        assertEquals(OK, lifecycleService.moveToTrash(ARTICLE, article.getId(), contentManager));
        mockMvc.perform(delete("/api/content-trash/article/" + article.getId())
                        .header("Authorization", bearer(contentManager)))
                .andExpect(status().isForbidden());
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .anyMatch(item -> item.itemId().equals(article.getId())));
        mockMvc.perform(delete("/api/content-trash/article/" + article.getId())
                        .header("Authorization", bearer(systemAdmin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("საბოლოო წაშლის 30-დღიანი ვადა ჯერ არ გასულა"));
    }

    @Test
    void namedAuthoritySetsAndReleasesHoldWhileRestoreAndPurgeFailClosed() throws Exception {
        User contentManager = user("hold-content", Role.CONTENT_ADMIN);
        User unlistedAdmin = user("hold-unlisted", Role.SYSTEM_ADMIN);
        User authority = userWithEmail("legal-hold-authority@example.test", Role.SYSTEM_ADMIN);
        Article article = archivedArticle();
        Long articleId = article.getId();
        assertEquals(OK, lifecycleService.moveToTrash(ARTICLE, articleId, contentManager));

        mockMvc.perform(post("/api/content-trash/article/" + articleId + "/legal-hold"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/content-trash/article/" + articleId + "/legal-hold")
                        .header("Authorization", bearer(unlistedAdmin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("legal hold მართვის უფლება არ გაქვთ"));
        assertEquals(ContentLifecycleService.Status.NOT_AUTHORIZED,
                lifecycleService.changeLegalHold(ARTICLE, articleId, true, unlistedAdmin));
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .anyMatch(item -> item.itemId().equals(articleId) && !item.legalHold()));

        mockMvc.perform(post("/api/content-trash/article/" + articleId + "/legal-hold")
                        .header("Authorization", bearer(authority)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("legal hold ჩართულია"));
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .anyMatch(item -> item.itemType().equals("article")
                        && item.itemId().equals(articleId) && item.legalHold()));

        AuditLog setAudit = audit("SET_LEGAL_HOLD", articleId);
        var setDetails = objectMapper.readTree(setAudit.getDetails());
        assertEquals(authority.getId(), setAudit.getAdminId());
        assertEquals(false, setDetails.at("/before/legal_hold").asBoolean());
        assertEquals(true, setDetails.at("/after/legal_hold").asBoolean());

        Timestamp due = Timestamp.valueOf(TbilisiTime.now().minusMinutes(1).toLocalDateTime());
        jdbcTemplate.update("UPDATE articles SET purge_after = ? WHERE id = ?", due, articleId);
        mockMvc.perform(post("/api/content-trash/article/" + articleId + "/restore")
                        .header("Authorization", bearer(contentManager)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.detail").value(
                        "მასალაზე მოქმედებს legal hold და მისი ცვლილება აკრძალულია"));
        mockMvc.perform(delete("/api/content-trash/article/" + articleId)
                        .header("Authorization", bearer(unlistedAdmin)))
                .andExpect(status().isLocked());

        mockMvc.perform(delete("/api/content-trash/article/" + articleId + "/legal-hold")
                        .header("Authorization", bearer(unlistedAdmin)))
                .andExpect(status().isForbidden());
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .anyMatch(item -> item.itemId().equals(articleId) && item.legalHold()));
        mockMvc.perform(delete("/api/content-trash/article/" + articleId + "/legal-hold")
                        .header("Authorization", bearer(authority)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("legal hold მოხსნილია"));

        AuditLog releaseAudit = audit("RELEASE_LEGAL_HOLD", articleId);
        var releaseDetails = objectMapper.readTree(releaseAudit.getDetails());
        assertEquals(true, releaseDetails.at("/before/legal_hold").asBoolean());
        assertEquals(false, releaseDetails.at("/after/legal_hold").asBoolean());

        mockMvc.perform(delete("/api/content-trash/article/" + articleId)
                        .header("Authorization", bearer(unlistedAdmin)))
                .andExpect(status().isOk());
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .noneMatch(item -> item.itemId().equals(articleId)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM articles WHERE id = ?", Integer.class, articleId));
    }

    @Test
    void invalidItemTypesAreRejectedBeforeAnyTrashMutation() throws Exception {
        User contentManager = user("trash-invalid-content", Role.CONTENT_ADMIN);
        User systemAdmin = user("trash-invalid-admin", Role.SYSTEM_ADMIN);
        User authority = userWithEmail("legal-hold-authority@example.test", Role.SYSTEM_ADMIN);
        Article article = archivedArticle();
        assertEquals(OK, lifecycleService.moveToTrash(ARTICLE, article.getId(), contentManager));
        long auditBefore = auditLogRepository.count();

        mockMvc.perform(post("/api/content-trash/invalid/" + article.getId() + "/restore")
                        .header("Authorization", bearer(contentManager)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/content-trash/invalid/" + article.getId())
                        .header("Authorization", bearer(systemAdmin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/content-trash/invalid/" + article.getId() + "/legal-hold")
                        .header("Authorization", bearer(authority)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/content-trash/invalid/" + article.getId() + "/legal-hold")
                        .header("Authorization", bearer(authority)))
                .andExpect(status().isBadRequest());

        assertEquals(auditBefore, auditLogRepository.count());
        assertTrue(lifecycleService.listTrash(contentManager).stream()
                .anyMatch(item -> item.itemId().equals(article.getId()) && !item.legalHold()));
    }

    private User user(String localPart, Role role) {
        return userWithEmail(localPart + "-" + System.nanoTime() + "@magti.ge", role);
    }

    private User userWithEmail(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setName("სანაგვის ტესტი");
        user.setRole(role);
        user.setDepartment("ტექნიკური");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private AuditLog audit(String action, Long itemId) {
        return auditLogRepository.findAll().stream()
                .filter(entry -> action.equals(entry.getAction())
                        && "article".equals(entry.getItemType())
                        && itemId.equals(entry.getItemId()))
                .findFirst().orElseThrow();
    }

    private Article archivedArticle() {
        Category category = new Category();
        category.setName("Trash " + System.nanoTime());
        category.setActive(true);
        category = categoryRepository.saveAndFlush(category);

        Article article = new Article();
        article.setTitle("აღდგენადი მასალა");
        article.setContent("<p>სატესტო შინაარსი</p>");
        article.setCategoryId(category.getId());
        article.setStatus("archived");
        article.setDraft(false);
        article.setVersion(1);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        return articleRepository.saveAndFlush(article);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.createAccessTokenFor(user);
    }
}
