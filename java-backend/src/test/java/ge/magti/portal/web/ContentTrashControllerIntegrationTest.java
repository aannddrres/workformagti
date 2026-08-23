package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static ge.magti.portal.content.ContentLifecycleService.ItemType.ARTICLE;
import static ge.magti.portal.content.ContentLifecycleService.Status.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest
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
                        .header("Authorization", bearer(contentManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("მასალა აღდგენილია"));

        assertEquals(OK, lifecycleService.moveToTrash(ARTICLE, article.getId(), contentManager));
        mockMvc.perform(delete("/api/content-trash/article/" + article.getId())
                        .header("Authorization", bearer(contentManager)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/content-trash/article/" + article.getId())
                        .header("Authorization", bearer(systemAdmin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("საბოლოო წაშლის 30-დღიანი ვადა ჯერ არ გასულა"));
    }

    private User user(String localPart, Role role) {
        User user = new User();
        user.setEmail(localPart + "-" + System.nanoTime() + "@magti.ge");
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
